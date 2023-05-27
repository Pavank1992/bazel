// Copyright 2014 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.skyframe;

import com.google.common.base.Preconditions;
import com.google.common.base.Stopwatch;
import com.google.common.collect.ImmutableSet;
import com.google.common.flogger.GoogleLogger;
import com.google.devtools.build.lib.actions.MutableActionGraph.ActionConflictException;
import com.google.devtools.build.lib.analysis.CachingAnalysisEnvironment;
import com.google.devtools.build.lib.analysis.CachingAnalysisEnvironment.MissingDepException;
import com.google.devtools.build.lib.analysis.ConfiguredRuleClassProvider;
import com.google.devtools.build.lib.analysis.ConfiguredTarget;
import com.google.devtools.build.lib.analysis.ConfiguredTargetFactory;
import com.google.devtools.build.lib.analysis.ConfiguredTargetValue;
import com.google.devtools.build.lib.analysis.Dependency;
import com.google.devtools.build.lib.analysis.DependencyKind;
import com.google.devtools.build.lib.analysis.DependencyResolver;
import com.google.devtools.build.lib.analysis.EmptyConfiguredTarget;
import com.google.devtools.build.lib.analysis.ExecGroupCollection;
import com.google.devtools.build.lib.analysis.ExecGroupCollection.InvalidExecGroupException;
import com.google.devtools.build.lib.analysis.InconsistentNullConfigException;
import com.google.devtools.build.lib.analysis.ResolvedToolchainContext;
import com.google.devtools.build.lib.analysis.TargetAndConfiguration;
import com.google.devtools.build.lib.analysis.ToolchainCollection;
import com.google.devtools.build.lib.analysis.config.BuildConfigurationValue;
import com.google.devtools.build.lib.analysis.config.ConfigConditions;
import com.google.devtools.build.lib.analysis.config.ConfigurationResolver;
import com.google.devtools.build.lib.analysis.config.TransitionResolver;
import com.google.devtools.build.lib.analysis.configuredtargets.RuleConfiguredTarget;
import com.google.devtools.build.lib.analysis.constraints.IncompatibleTargetChecker;
import com.google.devtools.build.lib.analysis.producers.TargetAndConfigurationProducer;
import com.google.devtools.build.lib.analysis.producers.TargetAndConfigurationProducer.TargetAndConfigurationError;
import com.google.devtools.build.lib.analysis.producers.TransitiveDependencyState;
import com.google.devtools.build.lib.analysis.test.AnalysisFailurePropagationException;
import com.google.devtools.build.lib.causes.AnalysisFailedCause;
import com.google.devtools.build.lib.causes.Cause;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.collect.nestedset.Order;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.events.EventKind;
import com.google.devtools.build.lib.events.StoredEventHandler;
import com.google.devtools.build.lib.packages.Package;
import com.google.devtools.build.lib.packages.RuleClassProvider;
import com.google.devtools.build.lib.packages.Target;
import com.google.devtools.build.lib.server.FailureDetails.Analysis;
import com.google.devtools.build.lib.server.FailureDetails.Analysis.Code;
import com.google.devtools.build.lib.server.FailureDetails.FailureDetail;
import com.google.devtools.build.lib.skyframe.ConfiguredTargetEvaluationExceptions.DependencyException;
import com.google.devtools.build.lib.skyframe.ConfiguredTargetEvaluationExceptions.ReportedException;
import com.google.devtools.build.lib.skyframe.ConfiguredTargetEvaluationExceptions.UnreportedException;
import com.google.devtools.build.lib.skyframe.SkyframeExecutor.BuildViewProvider;
import com.google.devtools.build.lib.skyframe.toolchains.ToolchainException;
import com.google.devtools.build.lib.skyframe.toolchains.UnloadedToolchainContext;
import com.google.devtools.build.lib.util.DetailedExitCode;
import com.google.devtools.build.lib.util.DetailedExitCode.DetailedExitCodeComparator;
import com.google.devtools.build.lib.util.OrderedSetMultimap;
import com.google.devtools.build.skyframe.SkyFunction;
import com.google.devtools.build.skyframe.SkyFunction.Environment.SkyKeyComputeState;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.build.skyframe.SkyValue;
import com.google.devtools.build.skyframe.state.Driver;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import javax.annotation.Nullable;

/**
 * SkyFunction for {@link ConfiguredTargetValue}s.
 *
 * <p>This class drives the analysis phase. For a review of the analysis phase, see {@link
 * com.google.devtools.build.lib.analysis.RuleConfiguredTargetFactory}.
 *
 * <p>This function computes a target's complete analysis: its input is a target label and
 * configuration and its output is the target's actions. This implicitly constructs the build's
 * configured target and action graphs because a target's dependencies must be evaluated before the
 * target itself. If the build has multiple top-level targets, this is called for each one, and the
 * build-wide configured target and action graphs are the merged combination of each top-level call.
 *
 * <p>Multiple helper classes support this work, all called directly or indirectly from here:
 *
 * <ol>
 *   <li>{@link PrerequisiteProducer}: Analysis consists of two important steps: computing the
 *       target's prerequisite dependencies and executing its rule logic. This class performs the
 *       first step. It also performs supporting computations like {@code config_setting} and
 *       toolchain resolution.
 *   <li>{@link DependencyResolver}: Helper for {@link PrerequisiteProducer}: figures out what this
 *       target's dependencies are and what their configurations should be.
 *   <li>{@link Dependency}: Structured representation of a single dependency.
 *   <li>{@link DependencyKind}: Structured representation of a dependency's type (e.g. rule
 *       attribute vs. toolchain dependency).
 *   <li>{@link TransitionResolver}: Helper for {@link DependencyResolver}: figures out which
 *       configuration transitions to apply to dependencies.
 *   <li>{@link ConfigurationResolver}: Helper for {@link DependencyResolver} instantiates
 *       dependencies' configurations
 *   <li>{@link AspectFunction}: Evaluates aspects attached to this target's dependencies.
 *   <li>{@link ConfiguredTargetFactory}: Executes this target's rule logic (and generally
 *       constructs its {@link ConfiguredTarget} once all prerequisites are ready).
 * </ol>
 *
 * <p>This list is not exhaustive.
 *
 * @see com.google.devtools.build.lib.analysis.RuleConfiguredTargetFactory
 */
public final class ConfiguredTargetFunction implements SkyFunction {
  private static final GoogleLogger logger = GoogleLogger.forEnclosingClass();

  private final BuildViewProvider buildViewProvider;
  private final RuleClassProvider ruleClassProvider;
  // TODO(b/185987566): Remove this semaphore.
  private final AtomicReference<Semaphore> cpuBoundSemaphore;
  @Nullable private final ConfiguredTargetProgressReceiver configuredTargetProgress;

  /**
   * Indicates whether the set of packages transitively loaded for a given {@link
   * ConfiguredTargetValue} will be needed later (see {@link
   * com.google.devtools.build.lib.analysis.ConfiguredObjectValue#getTransitivePackages}). If not,
   * they are not collected and stored.
   */
  private final boolean storeTransitivePackages;

  private final boolean shouldUnblockCpuWorkWhenFetchingDeps;

  ConfiguredTargetFunction(
      BuildViewProvider buildViewProvider,
      RuleClassProvider ruleClassProvider,
      AtomicReference<Semaphore> cpuBoundSemaphore,
      boolean storeTransitivePackages,
      boolean shouldUnblockCpuWorkWhenFetchingDeps,
      @Nullable ConfiguredTargetProgressReceiver configuredTargetProgress) {
    this.buildViewProvider = buildViewProvider;
    this.ruleClassProvider = ruleClassProvider;
    this.cpuBoundSemaphore = cpuBoundSemaphore;
    this.storeTransitivePackages = storeTransitivePackages;
    this.shouldUnblockCpuWorkWhenFetchingDeps = shouldUnblockCpuWorkWhenFetchingDeps;
    this.configuredTargetProgress = configuredTargetProgress;
  }

  private void maybeAcquireSemaphoreWithLogging(SkyKey key) throws InterruptedException {
    if (cpuBoundSemaphore.get() == null) {
      return;
    }
    Stopwatch stopwatch = Stopwatch.createStarted();
    cpuBoundSemaphore.get().acquire();
    long elapsedTime = stopwatch.elapsed().toMillis();
    if (elapsedTime > 5) {
      logger.atInfo().atMostEvery(10, TimeUnit.SECONDS).log(
          "Spent %s milliseconds waiting for lock acquisition for %s", elapsedTime, key);
    }
  }

  private void maybeReleaseSemaphore() {
    if (cpuBoundSemaphore.get() != null) {
      cpuBoundSemaphore.get().release();
    }
  }

  private static class State
      implements SkyKeyComputeState, TargetAndConfigurationProducer.ResultSink {
    /**
     * Drives a {@link TargetAndConfigurationProducer} that sets the {@link
     * #targetAndConfigurationResult} when complete.
     */
    @Nullable // Non-null while in-flight.
    private Driver targetAndConfigurationProducer;

    /**
     * Union-type output of {@link #targetAndConfigurationProducer}.
     *
     * <ul>
     *   <li>{@link ConfiguredTargetKey}: if the result was a {@link TargetAndConfiguration}, set in
     *       {@link PrerequisiteProducer.State#targetAndConfiguration}.
     *   <li>{@link ConfiguredTargetValue}: an immediate value. This occurs when applying the rule
     *       transition to the {@link ConfiguredTargetKey} results in a previously computed key.
     *   <li>{@link TargetAndConfigurationError}: if an error occurred.
     * </ul>
     */
    private Object targetAndConfigurationResult;

    /** Null if ConfiguredTargetFuncton is not storing this information. */
    @Nullable NestedSetBuilder<Package> transitivePackages;

    NestedSetBuilder<Cause> transitiveRootCauses = NestedSetBuilder.stableOrder();

    PrerequisiteProducer.State computeDependenciesState = new PrerequisiteProducer.State();

    State(boolean storeTransitivePackages) {
      this.transitivePackages = storeTransitivePackages ? NestedSetBuilder.stableOrder() : null;
    }

    @Override
    public void acceptTargetAndConfiguration(
        TargetAndConfiguration value, ConfiguredTargetKey fullKey) {
      computeDependenciesState.targetAndConfiguration = value;
      this.targetAndConfigurationResult = fullKey;
    }

    @Override
    public void acceptTargetAndConfigurationDelegatedValue(ConfiguredTargetValue value) {
      this.targetAndConfigurationResult = value;
    }

    @Override
    public void acceptTargetAndConfigurationError(TargetAndConfigurationError error) {
      this.targetAndConfigurationResult = error;
    }
  }

  @Nullable
  @Override
  public SkyValue compute(SkyKey key, Environment env)
      throws ReportedException, UnreportedException, DependencyException, InterruptedException {
    State state = env.getState(() -> new State(storeTransitivePackages));
    ConfiguredTargetKey configuredTargetKey = (ConfiguredTargetKey) key.argument();
    Preconditions.checkArgument(!configuredTargetKey.isProxy(), configuredTargetKey);
    SkyframeBuildView view = buildViewProvider.getSkyframeBuildView();

    if (shouldUnblockCpuWorkWhenFetchingDeps) {
      // Fetching blocks on other resources, so we don't want to hold on to the semaphore meanwhile.
      // TODO(b/194319860): remove this and PrerequisiteProducer.SemaphoreAcquirer when we no need
      // semaphore locking.
      env =
          new StateInformingSkyFunctionEnvironment(
              env,
              /* preFetch= */ this::maybeReleaseSemaphore,
              /* postFetch= */ () -> maybeAcquireSemaphoreWithLogging(key));
    }

    var computeDependenciesState = state.computeDependenciesState;
    if (computeDependenciesState.targetAndConfiguration == null) {
      try {
        computeTargetAndConfiguration(env, state, configuredTargetKey);
      } catch (InconsistentNullConfigException e) {
        // TODO(b/267529852): see if we can remove this. It's not clear the conditions that trigger
        // InconsistentNullConfigException are even possible.
        return new NonRuleConfiguredTargetValue(
            new EmptyConfiguredTarget(
                configuredTargetKey.getLabel(), configuredTargetKey.getConfigurationKey()),
            state.transitivePackages == null ? null : state.transitivePackages.build());
      }
      // Any `TargetAndConfigurationError` has already been handled, so `result` can only
      // be null, a `ConfiguredTargetKey` or a `ConfiguredTargetValue`.
      Object result = state.targetAndConfigurationResult;
      if (!(result instanceof ConfiguredTargetKey)) {
        return (ConfiguredTargetValue) result; // Null or an immediate `ConfiguredTargetValue`.
      }
      // Otherwise, `result` contains a `ConfiguredTargetKey`.
    }

    PrerequisiteProducer prereqs =
        new PrerequisiteProducer(computeDependenciesState.targetAndConfiguration);
    try {
      // Perform all analysis through dependency evaluation.
      if (!prereqs.evaluate(
          state.computeDependenciesState,
          configuredTargetKey.getExecutionPlatformLabel(),
          ruleClassProvider,
          view,
          () -> maybeAcquireSemaphoreWithLogging(key),
          new TransitiveDependencyState(
              state.transitiveRootCauses,
              state.transitivePackages,
              /* prerequisitePackages= */ null),
          env)) {
        return null;
      }
      Preconditions.checkNotNull(prereqs.getDepValueMap());

      // If one of our dependencies is platform-incompatible with this build, so are we.
      Optional<RuleConfiguredTargetValue> incompatibleTarget =
          IncompatibleTargetChecker.createIndirectlyIncompatibleTarget(
              prereqs.getTargetAndConfiguration(),
              prereqs.getDepValueMap(),
              prereqs.getConfigConditions(),
              prereqs.getPlatformInfo(),
              state.transitivePackages);
      if (incompatibleTarget.isPresent()) {
        return incompatibleTarget.get();
      }

      // Load the requested toolchains into the ToolchainContext, now that we have dependencies.
      ToolchainCollection<ResolvedToolchainContext> toolchainContexts = null;
      if (prereqs.getUnloadedToolchainContexts() != null) {
        String targetDescription = prereqs.getTargetAndConfiguration().getTarget().toString();
        ToolchainCollection.Builder<ResolvedToolchainContext> contextsBuilder =
            ToolchainCollection.builder();
        for (Map.Entry<String, UnloadedToolchainContext> unloadedContext :
            prereqs.getUnloadedToolchainContexts().getContextMap().entrySet()) {
          ImmutableSet<ConfiguredTargetAndData> toolchainDependencies =
              ImmutableSet.copyOf(
                  prereqs
                      .getDepValueMap()
                      .get(DependencyKind.forExecGroup(unloadedContext.getKey())));
          contextsBuilder.addContext(
              unloadedContext.getKey(),
              ResolvedToolchainContext.load(
                  unloadedContext.getValue(), targetDescription, toolchainDependencies));
        }
        toolchainContexts = contextsBuilder.build();
      }

      // Run this target's rule logic to create its actions and return its ConfiguredTargetValue.
      ConfiguredTargetValue ans =
          createConfiguredTarget(
              view,
              env,
              prereqs.getTargetAndConfiguration(),
              // If the ConfiguredTarget has no Actions, a DelegatingConfiguredTargetKey passed here
              // may fall out of the interning pool and lead to additional computation. It might be
              // worth retaining them in the created ConfiguredTargets instead.
              (ConfiguredTargetKey) state.targetAndConfigurationResult,
              prereqs.getDepValueMap(),
              prereqs.getConfigConditions(),
              toolchainContexts,
              state.computeDependenciesState.execGroupCollectionBuilder,
              state.transitivePackages);
      if (ans != null && configuredTargetProgress != null) {
        configuredTargetProgress.doneConfigureTarget();
      }
      return ans;
    } catch (IncompatibleTargetChecker.IncompatibleTargetException e) {
      return e.target();
    } catch (ConfiguredValueCreationException e) {
      if (!e.getMessage().isEmpty()) {
        // Report the error to the user.
        env.getListener().handle(Event.error(e.getLocation(), e.getMessage()));
      }
      throw new ReportedException(e);
    } catch (ToolchainException e) {
      ConfiguredValueCreationException cvce =
          e.asConfiguredValueCreationException(prereqs.getTargetAndConfiguration());
      env.getListener()
          .handle(
              Event.error(
                  prereqs.getTargetAndConfiguration().getTarget().getLocation(),
                  cvce.getMessage()));
      throw new ReportedException(cvce);
    } finally {
      maybeReleaseSemaphore();
    }
  }

  @Override
  public String extractTag(SkyKey skyKey) {
    return Label.print(((ConfiguredTargetKey) skyKey.argument()).getLabel());
  }

  @SuppressWarnings("LenientFormatStringValidation")
  @Nullable
  private static ConfiguredTargetValue createConfiguredTarget(
      SkyframeBuildView view,
      Environment env,
      TargetAndConfiguration ctgValue,
      ConfiguredTargetKey configuredTargetKey,
      OrderedSetMultimap<DependencyKind, ConfiguredTargetAndData> depValueMap,
      ConfigConditions configConditions,
      @Nullable ToolchainCollection<ResolvedToolchainContext> toolchainContexts,
      ExecGroupCollection.Builder execGroupCollectionBuilder,
      @Nullable NestedSetBuilder<Package> transitivePackagesBuilder)
      throws ConfiguredValueCreationException, InterruptedException {
    Target target = ctgValue.getTarget();
    BuildConfigurationValue configuration = ctgValue.getConfiguration();

    // Should be successfully evaluated and cached from the loading phase.
    StarlarkBuiltinsValue starlarkBuiltinsValue =
        (StarlarkBuiltinsValue) env.getValue(StarlarkBuiltinsValue.key());
    if (starlarkBuiltinsValue == null) {
      return null;
    }

    NestedSet<Package> transitivePackages =
        transitivePackagesBuilder == null ? null : transitivePackagesBuilder.build();
    StoredEventHandler events = new StoredEventHandler();
    CachingAnalysisEnvironment analysisEnvironment =
        view.createAnalysisEnvironment(
            configuredTargetKey, events, env, configuration, starlarkBuiltinsValue);

    Preconditions.checkNotNull(depValueMap);
    ConfiguredTarget configuredTarget;
    try {
      configuredTarget =
          view.createConfiguredTarget(
              target,
              configuration,
              analysisEnvironment,
              configuredTargetKey,
              depValueMap,
              configConditions,
              toolchainContexts,
              transitivePackages,
              execGroupCollectionBuilder);
    } catch (MissingDepException e) {
      Preconditions.checkState(env.valuesMissing(), e.getMessage());
      return null;
    } catch (ActionConflictException e) {
      e.reportTo(env.getListener());
      throw new ConfiguredValueCreationException(ctgValue, e.getMessage());
    } catch (InvalidExecGroupException e) {
      throw new ConfiguredValueCreationException(ctgValue, e.getMessage());
    } catch (AnalysisFailurePropagationException e) {
      throw new ConfiguredValueCreationException(
          ctgValue, e.getMessage(), /* rootCauses = */ null, e.getDetailedExitCode());
    }

    events.replayOn(env.getListener());
    if (events.hasErrors()) {
      analysisEnvironment.disable(target);
      NestedSet<Cause> rootCauses =
          NestedSetBuilder.wrap(
              Order.STABLE_ORDER,
              events.getEvents().stream()
                  .filter((event) -> event.getKind() == EventKind.ERROR)
                  .map(
                      (event) ->
                          new AnalysisFailedCause(
                              target.getLabel(),
                              configuration == null
                                  ? null
                                  : configuration.getEventId().getConfiguration(),
                              createDetailedExitCode(event.getMessage())))
                  .collect(Collectors.toList()));
      throw new ConfiguredValueCreationException(
          ctgValue, "Analysis of target '" + target.getLabel() + "' failed", rootCauses, null);
    }
    Preconditions.checkState(
        !analysisEnvironment.hasErrors(), "Analysis environment hasError() but no errors reported");
    if (env.valuesMissing()) {
      return null;
    }

    analysisEnvironment.disable(target);
    Preconditions.checkNotNull(configuredTarget, target);

    if (configuredTarget instanceof RuleConfiguredTarget) {
      RuleConfiguredTarget ruleConfiguredTarget = (RuleConfiguredTarget) configuredTarget;
      return new RuleConfiguredTargetValue(ruleConfiguredTarget, transitivePackages);
    } else {
      // Expected 4 args, but got 3.
      Preconditions.checkState(
          analysisEnvironment.getRegisteredActions().isEmpty(),
          "Non-rule can't have actions: %s %s %s",
          configuredTargetKey,
          analysisEnvironment.getRegisteredActions(),
          configuredTarget);
      return new NonRuleConfiguredTargetValue(configuredTarget, transitivePackages);
    }
  }

  private void computeTargetAndConfiguration(
      Environment env, State state, ConfiguredTargetKey configuredTargetKey)
      throws DependencyException,
          InconsistentNullConfigException,
          ReportedException,
          InterruptedException {
    StoredEventHandler storedEvents = state.computeDependenciesState.storedEvents;
    Object result = null;
    boolean completedWithoutExceptions = false;
    try {
      if (state.targetAndConfigurationProducer == null) {
        state.targetAndConfigurationProducer =
            new Driver(
                new TargetAndConfigurationProducer(
                    configuredTargetKey,
                    ((ConfiguredRuleClassProvider) ruleClassProvider)
                        .getTrimmingTransitionFactory(),
                    buildViewProvider.getSkyframeBuildView().getStarlarkTransitionCache(),
                    new TransitiveDependencyState(
                        state.transitiveRootCauses,
                        state.transitivePackages,
                        /* prerequisitePackages= */ null),
                    (TargetAndConfigurationProducer.ResultSink) state));
      }
      if (state.targetAndConfigurationProducer.drive(env, storedEvents)) {
        state.targetAndConfigurationProducer = null;
      }
      result = state.targetAndConfigurationResult;
      if (result instanceof TargetAndConfigurationError) {
        var error = (TargetAndConfigurationError) result;
        switch (error.kind()) {
          case CONFIGURED_VALUE_CREATION:
            ConfiguredValueCreationException e = error.configuredValueCreation();
            if (!e.getMessage().isEmpty()) {
              // Reports the error to the user on storedEvents to preserve ordering. These will
              // be immediately replayed in the finally clause.
              storedEvents.handle(Event.error(e.getLocation(), e.getMessage()));
            }
            throw new ReportedException(e);
          case INVALID_VISIBILITY_DEPENDENCY:
            // Bubbles the error up to the parent ConfiguredTargetFunction where it will be reported
            // with additional context.
            throw new DependencyException(error.invalidVisibilityDependency());
          case INCONSISTENT_NULL_CONFIG:
            throw error.inconsistentNullConfig();
        }
      }
      completedWithoutExceptions = true; // Marks the fact that there were no exceptions.
    } finally {
      // If there is exception or an immediate value ...
      if (!completedWithoutExceptions || result instanceof ConfiguredTargetValue) {
        // ... replays events because `ConfiguredTargetFunction.compute` will promptly end.
        storedEvents.replayOn(env.getListener());
      }
      // Otherwise either:
      // 1. the result is null for a restart, so replayed events would not be used anyway; or
      // 2. the result is a `TargetAndConfiguration` value and
      //    `PrerequisiteProducer.computeDependencies` takes ownership of stored events.
    }
  }

  private static DetailedExitCode createDetailedExitCode(String message) {
    return DetailedExitCode.of(
        FailureDetail.newBuilder()
            .setMessage(message)
            .setAnalysis(Analysis.newBuilder().setCode(Code.CONFIGURED_VALUE_CREATION_FAILED))
            .build());
  }

  static DetailedExitCode getPrioritizedDetailedExitCode(NestedSet<Cause> causes) {
    DetailedExitCode prioritizedDetailedExitCode = null;
    for (Cause c : causes.toList()) {
      prioritizedDetailedExitCode =
          DetailedExitCodeComparator.chooseMoreImportantWithFirstIfTie(
              prioritizedDetailedExitCode, c.getDetailedExitCode());
    }
    return prioritizedDetailedExitCode;
  }
}
