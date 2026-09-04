package io.github.psd2live.agent

import java.time.Instant
import java.util.UUID

/**
 * In-process checkpoint log for an external Agent. It records the Agent's evolving plan; it does not
 * prescribe workflows or execute hidden scripts. Events are append-only for the lifetime of the app.
 */
internal class AgentTaskManager(
	private val clock: () -> Instant = Instant::now,
	private val newId: () -> String = { "task-${UUID.randomUUID()}" },
) {
	private data class MutableTask(
		val id: String,
		val objective: String,
		var plan: List<String>,
		var status: AgentTaskStatus,
		var currentStep: Int?,
		var progress: Float,
		val inputRevisionId: String,
		val inputHistoryHeadNodeId: String,
		val createdAt: Instant,
		var updatedAt: Instant,
		val artifactIds: LinkedHashSet<String>,
		val events: MutableList<AgentTaskEventSnapshot>,
	)

	private val tasksById = linkedMapOf<String, MutableTask>()
	private var eventSequence = 0L

	@Synchronized
	fun restore(snapshots: List<AgentTaskSnapshot>) {
		require(tasksById.isEmpty()) { "Task manager is already initialized" }
		require(snapshots.map { it.id }.toSet().size == snapshots.size) { "Persisted task IDs must be unique" }
		for (snapshot in snapshots) {
			require(snapshot.id.isNotBlank() && snapshot.objective.isNotBlank()) { "Persisted task identity is incomplete" }
			require(snapshot.plan.isNotEmpty()) { "Persisted task plan must not be empty" }
			require(snapshot.progress.isFinite() && snapshot.progress in 0f..1f) { "Persisted task progress is invalid" }
			snapshot.currentStep?.let { require(it in snapshot.plan.indices) { "Persisted current task step is invalid" } }
			val createdAt = Instant.parse(snapshot.createdAt)
			val updatedAt = Instant.parse(snapshot.updatedAt)
			val events = snapshot.events.sortedBy { it.sequence }.toMutableList()
			require(events.map { it.sequence }.toSet().size == events.size) { "Persisted task event sequences must be unique" }
			eventSequence = maxOf(eventSequence, events.maxOfOrNull { it.sequence } ?: 0L)
			tasksById[snapshot.id] = MutableTask(
				id = snapshot.id,
				objective = snapshot.objective,
				plan = snapshot.plan.toList(),
				status = snapshot.status,
				currentStep = snapshot.currentStep,
				progress = snapshot.progress,
				inputRevisionId = snapshot.inputRevisionId,
				inputHistoryHeadNodeId = snapshot.inputHistoryHeadNodeId,
				createdAt = createdAt,
				updatedAt = updatedAt,
				artifactIds = LinkedHashSet(snapshot.artifactIds),
				events = events,
			)
		}
	}

	@Synchronized
	fun start(
		objective: String,
		plan: List<String>,
		inputRevisionId: String,
		inputHistoryHeadNodeId: String,
	): AgentTaskSnapshot {
		val normalizedObjective = objective.trim()
		require(normalizedObjective.isNotEmpty()) { "Task objective must not be blank" }
		val normalizedPlan = plan.map(String::trim).filter(String::isNotEmpty)
		require(normalizedPlan.isNotEmpty()) { "Task plan must contain at least one step" }
		val now = clock()
		val id = uniqueId()
		val task = MutableTask(
			id = id,
			objective = normalizedObjective,
			plan = normalizedPlan,
			status = AgentTaskStatus.PLANNING,
			currentStep = 0,
			progress = 0f,
			inputRevisionId = inputRevisionId,
			inputHistoryHeadNodeId = inputHistoryHeadNodeId,
			createdAt = now,
			updatedAt = now,
			artifactIds = linkedSetOf(),
			events = mutableListOf(),
		)
		tasksById[id] = task
		task.appendEvent("Task created from Agent plan", emptyList(), now)
		return task.snapshot()
	}

	@Synchronized
	fun update(
		taskId: String,
		status: AgentTaskStatus,
		plan: List<String>?,
		currentStep: Int?,
		progress: Float?,
		message: String,
		artifactIds: List<String>,
	): AgentTaskSnapshot {
		val task = requireTask(taskId)
		val replacementPlan = plan?.map(String::trim)?.filter(String::isNotEmpty)
		replacementPlan?.let { require(it.isNotEmpty()) { "Task plan must contain at least one step" } }
		val effectivePlan = replacementPlan ?: task.plan
		currentStep?.let { require(it in effectivePlan.indices) { "current_step must identify a plan step" } }
		progress?.let { require(it.isFinite() && it in 0f..1f) { "progress must be within 0..1" } }
		val normalizedArtifacts = artifactIds.map(String::trim).filter(String::isNotEmpty).distinct()
		val now = clock()
		task.status = status
		if (replacementPlan != null) task.plan = replacementPlan
		if (currentStep != null) task.currentStep = currentStep
		if (progress != null) task.progress = progress
		if (status == AgentTaskStatus.DONE) task.progress = 1f
		task.artifactIds += normalizedArtifacts
		task.updatedAt = now
		task.appendEvent(message.trim().ifEmpty { status.name.lowercase() }, normalizedArtifacts, now)
		return task.snapshot()
	}

	@Synchronized
	fun get(taskId: String): AgentTaskSnapshot = requireTask(taskId).snapshot()

	@Synchronized
	fun list(): List<AgentTaskSnapshot> = tasksById.values.map { it.snapshot() }

	private fun requireTask(taskId: String): MutableTask =
		tasksById[taskId] ?: throw IllegalArgumentException("Agent task not found: $taskId")

	private fun uniqueId(): String {
		var candidate = newId()
		require(candidate.isNotBlank()) { "Task ID must not be blank" }
		while (candidate in tasksById) candidate = newId()
		return candidate
	}

	private fun MutableTask.appendEvent(message: String, artifacts: List<String>, now: Instant) {
		events += AgentTaskEventSnapshot(++eventSequence, now.toString(), status, message, artifacts)
	}

	private fun MutableTask.snapshot(): AgentTaskSnapshot = AgentTaskSnapshot(
		id = id,
		objective = objective,
		plan = plan.toList(),
		status = status,
		currentStep = currentStep,
		progress = progress,
		inputRevisionId = inputRevisionId,
		inputHistoryHeadNodeId = inputHistoryHeadNodeId,
		createdAt = createdAt.toString(),
		updatedAt = updatedAt.toString(),
		artifactIds = artifactIds.toList(),
		events = events.toList(),
	)
}
