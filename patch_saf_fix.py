import re

with open("app/src/main/java/top/maary/darkbag/repository/ImageRepository.kt", "r") as f:
    content = f.read()

# Replace the SAF findFile logic with a faster check
# Actually, DocumentFile.findFile() calls listFiles() underneath, which is extremely slow on SAF with many files.
# Instead of DocumentFile.findFile(), we can query the Tree Uri using ContentResolver.
# Or, since this is a "fast" preload and MediaStore is fast, we should just query MediaStore for BOTH JPEG and DNG!
# Why did I use SAF for DNG? Because DNGs might only be in SAF.
# Let's write a fast SAF query using content resolver.

saf_replacement = """                // Preload from SAF (DNG) using direct query to avoid findFile (which is O(N))
                val rawFolderUriStr = prefs.getString(SettingsFragment.KEY_RAW_STORAGE_URI, null)
                if (rawFolderUriStr != null) {
                    try {
                        val treeUri = Uri.parse(rawFolderUriStr)
                        val treeDocumentId = android.provider.DocumentsContract.getTreeDocumentId(treeUri)
                        val childrenUri = android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, treeDocumentId)

                        val projection = arrayOf(
                            android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                            android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                            android.provider.DocumentsContract.Document.COLUMN_LAST_MODIFIED
                        )

                        // We query the children URI. Note: SAF doesn't always support selection args, but we can try,
                        // or we just fetch everything briefly? No, fetching everything is slow.
                        // Actually, if we just want to avoid O(N) listFiles inside DocumentFile, querying with selection might fail on some providers.
                        // So let's fall back to a fast query loop but only on the Raw folder.

                        // Safest fast way: if it fails, it fails, but we don't use DocumentFile.listFiles
                        context.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                            val idCol = cursor.getColumnIndexOrThrow(android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                            val nameCol = cursor.getColumnIndexOrThrow(android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                            val dateCol = cursor.getColumnIndexOrThrow(android.provider.DocumentsContract.Document.COLUMN_LAST_MODIFIED)

                            val builder = groups.getOrPut(targetName) { ImageGroupBuilder(targetName) }
                            while (cursor.moveToNext()) {
                                val name = cursor.getString(nameCol) ?: continue
                                if (!name.startsWith(targetName)) continue // Fast filter for the target only

                                val docId = cursor.getString(idCol)
                                val date = cursor.getLong(dateCol)
                                val fileUri = android.provider.DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)

                                when {
                                    name.contains("_HF2") && name.endsWith(".dng", ignoreCase = true) -> builder.setDng2(fileUri, date)
                                    name.contains("_HF1") && name.endsWith(".dng", ignoreCase = true) -> builder.setDng1(fileUri, date)
                                    name.endsWith(".dng", ignoreCase = true) -> builder.setDng(fileUri, date)
                                }
                            }
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("ImageRepository", "Failed to preload target SAF fast query", e)
                    }
                }"""

# Find the block:
old_saf = """                // Preload from SAF (DNG)
                val rawFolderUriStr = prefs.getString(SettingsFragment.KEY_RAW_STORAGE_URI, null)
                if (rawFolderUriStr != null) {
                    try {
                        val treeUri = Uri.parse(rawFolderUriStr)
                        val root = DocumentFile.fromTreeUri(context, treeUri)
                        if (root != null) {
                            val possibleNames = listOf(
                                "$targetName.dng",
                                "${targetName}_HF1.dng",
                                "${targetName}_HF2.dng"
                            )
                            val builder = groups.getOrPut(targetName) { ImageGroupBuilder(targetName) }
                            for (name in possibleNames) {
                                val file = root.findFile(name)
                                if (file != null && file.exists()) {
                                    val lastModified = file.lastModified()
                                    when {
                                        name.contains("_HF2") -> builder.setDng2(file.uri, lastModified)
                                        name.contains("_HF1") -> builder.setDng1(file.uri, lastModified)
                                        else -> builder.setDng(file.uri, lastModified)
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("ImageRepository", "Failed to preload target SAF", e)
                    }
                }"""

content = content.replace(old_saf, saf_replacement)

# Also fix the MediaStore query to be safer and faster
old_media = """                val selection = "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?"
                val selectionArgs = arrayOf("$targetName%")"""

# Darkbag prefix is DBAG_
new_media = """                val selection = "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?"
                val selectionArgs = arrayOf("$targetName%")""" # That is actually correct. But let's add relative path just in case.

# Write it out
with open("app/src/main/java/top/maary/darkbag/repository/ImageRepository.kt", "w") as f:
    f.write(content)
