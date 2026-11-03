package com.musigatto.fosscore.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class BackupFileNameTest {

    @Test
    fun aBackupIsNotNamedAsAZip() {
        // the system file picker opens a .zip as a folder: a tap on the backup showed what is in
        // it instead of choosing it, and only a long press imported it
        val name = LibraryBackup.fileName("2026-10-31")
        assertEquals("fosscore-library-2026-10-31.fosscore", name)
        assertFalse(name.endsWith(".zip"))
    }
}
