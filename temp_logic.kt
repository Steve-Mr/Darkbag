            } catch (e: Exception) {
                Log.e(TAG, "HDR+ processing failed, falling back to single shot", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(requireContext(), "HDR+ failed, saving single frame...", Toast.LENGTH_SHORT).show()
                }
                if (frames.isNotEmpty()) {
                    try {
                        val firstFrame = frames[0]
                        val currentZoom = if (is2xMode) 2.0f else (currentFocalLength / 24.0f)
                        val holder = copyImageToHolder(firstFrame, currentZoom)
                        processingChannel.send(holder)
                        fallbackSent = true
                    } catch (fallbackEx: Exception) {
                        Log.e(TAG, "Fallback failed", fallbackEx)
                    }
                }
            } finally {
                frames.forEach { it.close() }
                if (!fallbackSent) {
                    processingSemaphore.release()
                }
            }
        }
    }
