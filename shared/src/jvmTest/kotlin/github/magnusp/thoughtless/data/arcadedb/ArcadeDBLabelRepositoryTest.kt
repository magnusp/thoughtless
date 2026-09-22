package github.magnusp.thoughtless.data.arcadedb

import github.magnusp.thoughtless.domain.model.Label
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ArcadeDBLabelRepositoryTest {

    private lateinit var tempDir: File
    private lateinit var engine: ArcadeDBEngine
    private lateinit var labelRepo: ArcadeDBLabelRepository
    private lateinit var taskRepo: ArcadeDBTaskRepository

    @BeforeTest
    fun setup() {
        tempDir = Files.createTempDirectory("label-repo-test").toFile()
        engine = ArcadeDBEngine(tempDir.absolutePath)
        labelRepo = ArcadeDBLabelRepository(engine)
        taskRepo = ArcadeDBTaskRepository(engine)
    }

    @AfterTest
    fun tearDown() {
        engine.close()
        tempDir.deleteRecursively()
    }

    @Test
    fun testLabelCrudAndTaskAssociation() = runBlocking {
        val label1 = labelRepo.createLabel("Bug", "#EF4444")
        val label2 = labelRepo.createLabel("Feature", "#3B82F6")

        assertNotNull(label1.id)
        assertEquals("Bug", label1.name)
        assertEquals("#EF4444", label1.color)

        val allLabels = labelRepo.getLabels().first()
        assertEquals(2, allLabels.size)

        val task = taskRepo.createTask("Fix critical crash")

        // Associate label1 and label2 with task
        labelRepo.addLabelToTask(task.id, label1.id)
        labelRepo.addLabelToTask(task.id, label2.id)

        val taskLabels = labelRepo.getLabelsForTask(task.id).first()
        assertEquals(2, taskLabels.size)
        assertTrue(taskLabels.any { it.id == label1.id })
        assertTrue(taskLabels.any { it.id == label2.id })

        // Remove label1
        labelRepo.removeLabelFromTask(task.id, label1.id)
        val afterRemove = labelRepo.getLabelsForTask(task.id).first()
        assertEquals(1, afterRemove.size)
        assertEquals(label2.id, afterRemove.first().id)

        // Clear task labels
        labelRepo.clearTaskLabels(task.id)
        val afterClear = labelRepo.getLabelsForTask(task.id).first()
        assertEquals(0, afterClear.size)

        // Delete label
        labelRepo.deleteLabel(label2.id)
        val remainingLabels = labelRepo.getLabels().first()
        assertEquals(1, remainingLabels.size)
        assertEquals(label1.id, remainingLabels.first().id)
    }
}
