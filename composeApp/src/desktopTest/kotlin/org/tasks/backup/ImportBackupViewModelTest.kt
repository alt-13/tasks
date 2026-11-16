package org.tasks.backup

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okio.Buffer
import okio.Source
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.mock
import org.tasks.backup.ImportBackupViewModel.State

@OptIn(ExperimentalCoroutinesApi::class)
class ImportBackupViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private var picked = true

    private val picker = object : BackupFilePicker {
        override suspend fun <T> pick(read: suspend (String, () -> Source) -> T): T? =
            if (picked) read("backup.json") { Buffer() } else null
    }

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun showsResultOfImport() = runTest(dispatcher) {
        val result = BackupImporter.ImportResult()
        val importer = mock<BackupImporter> { onBlocking { importTasks(any(), anyOrNull()) } doReturn result }
        val viewModel = ImportBackupViewModel(picker, importer)

        viewModel.import()
        advanceUntilIdle()

        assertEquals(State.Done("backup.json", result), viewModel.state.value)
    }

    @Test
    fun staysIdleWhenPickerIsCancelled() = runTest(dispatcher) {
        picked = false
        val viewModel = ImportBackupViewModel(picker, mock())

        viewModel.import()
        advanceUntilIdle()

        assertEquals(State.Idle, viewModel.state.value)
    }

    @Test
    fun showsFailure() = runTest(dispatcher) {
        val importer = mock<BackupImporter> {
            onBlocking { importTasks(any(), anyOrNull()) } doThrow IllegalStateException()
        }
        val viewModel = ImportBackupViewModel(picker, importer)

        viewModel.import()
        advanceUntilIdle()

        assertEquals(State.Failed, viewModel.state.value)
    }
}
