package com.wraithhawit.rstweaks.test;

import com.refinedmods.refinedstorage.api.autocrafting.Pattern;
import com.refinedmods.refinedstorage.api.autocrafting.PatternLayout;
import com.refinedmods.refinedstorage.api.autocrafting.calculation.CancellationToken;
import com.refinedmods.refinedstorage.api.autocrafting.status.TaskStatus;
import com.refinedmods.refinedstorage.api.autocrafting.task.ExternalPatternSink;
import com.refinedmods.refinedstorage.api.autocrafting.task.Task;
import com.refinedmods.refinedstorage.api.autocrafting.task.TaskId;
import com.refinedmods.refinedstorage.api.core.Action;
import com.refinedmods.refinedstorage.api.network.Network;
import com.refinedmods.refinedstorage.api.network.autocrafting.AutocraftingNetworkComponent;
import com.refinedmods.refinedstorage.api.network.autocrafting.ParentContainer;
import com.refinedmods.refinedstorage.api.network.autocrafting.PatternProvider;
import com.refinedmods.refinedstorage.api.network.impl.autocrafting.AutocraftingNetworkComponentImpl;
import com.refinedmods.refinedstorage.api.network.impl.autocrafting.TaskContainer;
import com.refinedmods.refinedstorage.api.network.node.NetworkNode;
import com.refinedmods.refinedstorage.api.network.node.container.NetworkNodeContainer;
import com.refinedmods.refinedstorage.api.resource.ResourceAmount;
import com.refinedmods.refinedstorage.api.resource.ResourceKey;
import com.refinedmods.refinedstorage.api.storage.Actor;
import com.refinedmods.refinedstorage.api.storage.StorageImpl;
import com.refinedmods.refinedstorage.api.storage.root.RootStorageImpl;
import com.wraithhawit.rstweaks.Config;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.annotation.Nullable;

/**
 * How many crafting tasks a repeatedly-asking Exporter actually starts (issue #14).
 *
 * <p>The report is that an Exporter or Requester with a crafting upgrade keeps requesting
 * more of a resource while a craft for it is already in flight, most visibly with Cable
 * Tiers' tiered versions because they act several times per tick. The issue asks for one
 * thing before anything is designed: <b>confirm the duplicate requests are real tasks
 * rather than repeated no-op calls.</b>
 *
 * <p>Reading the code says they should not be. {@code ensureTask} sums
 * {@code provider.getAmount(resource)} across every pattern provider and returns
 * {@code TASK_ALREADY_RUNNING} when that reaches the amount asked for; {@code TaskContainer}
 * holds its tasks in a {@code CopyOnWriteArrayList} added to synchronously, so a task
 * created by one call is visible to the next even within a tick; and Cable Tiers builds its
 * exporter from Refined Storage's own registered factories, so it inherits the wrapper that
 * makes the check. But reading the code is how this project has produced three confidently
 * wrong answers, so this measures instead.
 *
 * <p>Each scenario drives {@code ensureTask} exactly as
 * {@code MissingResourcesListeningExporterTransferStrategy} does — same arguments, same
 * order — and counts the tasks the provider is actually handed. The amounts come from
 * {@code ExporterTransferQuotaProvider}: 1 for a plain exporter, 64 with a stack upgrade,
 * and the whole outstanding shortfall with a regulator upgrade, which is the one that does
 * not stay constant between calls.
 */
public final class AutocraftingRequestSelfTest {
    private static final Actor ACTOR = () -> "rstweaks-request-test";

    private AutocraftingRequestSelfTest() {
    }

    /**
     * A pattern provider that is nothing but a real {@link TaskContainer}.
     *
     * <p>That container is the class that actually answers "is one already running", and
     * {@code PatternProviderNetworkNode} adds nothing to it but block-entity plumbing. So
     * this is the autocrafter as far as the question is concerned, without a world.
     */
    private static final class CountingProvider implements PatternProvider, NetworkNode {
        private final TaskContainer tasks = new TaskContainer(this);
        private final List<Task> received = new ArrayList<>();

        @Nullable
        private Network network;

        @Override
        public void addTask(final Task task) {
            this.received.add(task);
            this.tasks.add(task, List.of());
        }

        @Override
        public long getAmount(final ResourceKey resource) {
            return this.tasks.getAmount(resource);
        }

        @Override
        public void onAddedIntoContainer(final ParentContainer parent) {
            this.tasks.onAddedIntoContainer(parent);
        }

        @Override
        public void onRemovedFromContainer(final ParentContainer parent) {
            this.tasks.onRemovedFromContainer(parent);
        }

        @Override
        public void cancelTask(final TaskId taskId) {
            this.tasks.cancel(taskId);
        }

        @Override
        public List<TaskStatus> getTaskStatuses() {
            return this.tasks.getStatuses();
        }

        @Override
        public void receivedExternalIteration() {
        }

        @Override
        public ExternalPatternSink.Result accept(final Pattern pattern,
                                                 final Collection<ResourceAmount> resources,
                                                 final Action action) {
            // Nothing here is an external (machine) pattern, so this is never reached.
            return ExternalPatternSink.Result.REJECTED;
        }

        @Nullable
        @Override
        public Network getNetwork() {
            return this.network;
        }

        @Override
        public void setNetwork(@Nullable final Network network) {
            this.network = network;
        }
    }

    /**
     * @param quota what the exporter asks for on each call, as
     *     {@code ExporterTransferQuotaProvider} would compute it. Given the call index so a
     *     regulator's shrinking shortfall can be modelled.
     */
    /**
     * @param stockTasks how many tasks stock Refined Storage starts, i.e. with
     *     {@code waitForRunningCraft} off. Asserted as well as the fixed behaviour, because
     *     a scenario that stopped reproducing would otherwise report the fix working when
     *     there was nothing left to fix. The regulator case is the only one above 1, and
     *     that number is the whole of issue #14.
     */
    private record Scenario(String name,
                            int calls,
                            java.util.function.LongUnaryOperator quota,
                            int stockTasks) {
    }

    public static CraftingPlanSelfTest.Result run() {
        final List<Scenario> scenarios = scenarios();
        final List<String> failures = new ArrayList<>();
        final boolean originalPlanner = Config.lpPlanner;
        final boolean originalWait = Config.waitForRunningCraft;
        try {
            // Stock Refined Storage's calculator, because the question is about RS's own
            // duplicate suppression and the planner has no bearing on it.
            Config.lpPlanner = false;
            for (final Scenario scenario : scenarios) {
                try {
                    // Stock behaviour first, then ours, against identical fixtures. Both
                    // are asserted: the pair is what shows the fix changes the case it is
                    // meant to and leaves the four it is not meant to touch alone.
                    Config.waitForRunningCraft = false;
                    check(scenario, "without waitForRunningCraft", scenario.stockTasks(), failures);
                    Config.waitForRunningCraft = true;
                    check(scenario, "with waitForRunningCraft", 1, failures);
                } catch (final RuntimeException e) {
                    failures.add(scenario.name() + ": threw " + e);
                }
            }
        } finally {
            Config.lpPlanner = originalPlanner;
            Config.waitForRunningCraft = originalWait;
        }
        return new CraftingPlanSelfTest.Result(scenarios.size() * 2, failures);
    }

    private static void check(final Scenario scenario,
                              final String mode,
                              final int expectedTasks,
                              final List<String> failures) {
        final ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            final StorageImpl source = new StorageImpl();
            source.insert(res("ingot"), 4096L, Action.EXECUTE, ACTOR);
            final RootStorageImpl storage = new RootStorageImpl();
            storage.addSource(source);

            final AutocraftingNetworkComponentImpl component =
                new AutocraftingNetworkComponentImpl(() -> storage, executor);
            final CountingProvider provider = new CountingProvider();
            final NetworkNodeContainer container = () -> provider;
            component.onContainerAdded(container);
            component.add(provider, gearPattern(), 0);

            final List<String> results = new ArrayList<>();
            for (int call = 0; call < scenario.calls(); call++) {
                final long amount = scenario.quota().applyAsLong(call);
                if (amount <= 0L) {
                    // What the exporter itself does before ever asking.
                    results.add("skipped");
                    continue;
                }
                results.add(String.valueOf(component.ensureTask(
                    res("gear"), amount, ACTOR, CancellationToken.NONE)));
            }

            final int tasks = provider.received.size();
            if (tasks != expectedTasks) {
                failures.add(scenario.name() + " [" + mode + "]: " + scenario.calls()
                    + " requests produced " + tasks + " tasks, expected " + expectedTasks
                    + ". Results in order: " + results + ". Amounts: " + amounts(scenario));
            }
        } finally {
            executor.shutdownNow();
        }
    }

    private static String amounts(final Scenario scenario) {
        final List<Long> out = new ArrayList<>();
        for (int call = 0; call < scenario.calls(); call++) {
            out.add(scenario.quota().applyAsLong(call));
        }
        return out.toString();
    }

    private static List<Scenario> scenarios() {
        final List<Scenario> out = new ArrayList<>();

        // A plain Exporter with an autocrafting upgrade: baseTransferQuota is 1, every tick.
        // Stock Refined Storage already gets this right, and the four scenarios that do are
        // here to hold the fix to touching only the one that does not.
        out.add(new Scenario("plain exporter asking every tick", 20, call -> 1L, 1));

        // The tiered case the issue names. Cable Tiers loops getSpeed() times inside ONE
        // doWork(), so these calls all land in the same tick with nothing stepped between
        // them -- which is the moment a check that relied on a task being visible only
        // next tick would fall apart. It does not: stock RS starts one task here.
        out.add(new Scenario("tiered exporter, eight requests in one tick", 8, call -> 1L, 1));

        // With a stack upgrade the quota is 64 rather than 1. Still constant, so still one.
        out.add(new Scenario("stack upgrade, quota 64", 20, call -> 64L, 1));

        // Regulator upgrade: the autocrafting quota provider is built with
        // respectTransferQuotaWhenRegulating = false, so the amount is the WHOLE outstanding
        // shortfall rather than a transfer quota. While the destination fills, the shortfall
        // shrinks and never exceeds what is running, so stock RS is fine.
        out.add(new Scenario("regulator, shortfall shrinking", 20,
            call -> Math.max(1L, 512L - call * 16L), 1));

        // And this is issue #14. Drain the destination faster than it fills -- a machine
        // eating from a regulated buffer -- and the shortfall GROWS. Every increase is
        // larger than what is running, so ensureTask tops up the difference with another
        // task, every single time. Twenty requests, twenty tasks, all TASK_CREATED.
        out.add(new Scenario("regulator, shortfall growing", 20,
            call -> 8L + call * 16L, 20));

        return out;
    }

    // ---------------------------------------------------------------- fixtures

    private static ResourceKey res(final String id) {
        return PlannerExecutabilitySelfTest.res(id);
    }

    private static Pattern gearPattern() {
        return new Pattern(
            UUID.nameUUIDFromBytes("rstweaks-request:gear".getBytes(StandardCharsets.UTF_8)),
            PatternLayout.internal(
                List.of(new com.refinedmods.refinedstorage.api.autocrafting.Ingredient(
                    4L, List.of(res("ingot")))),
                List.of(new ResourceAmount(res("gear"), 1L)),
                List.of()));
    }
}
