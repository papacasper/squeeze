package com.papacasper.squeeze

/**
 * Decides, partway through an encode pass, whether it is going to miss the size goal by so much that finishing it
 * is wasted time. A pass over a long clip costs minutes, and the next pass starts from the projected size anyway.
 */
object PassProjection {
    /** Below this much of the clip, the output so far says too little (rate control front-loads and varies). */
    const val MIN_PROGRESS = 0.4f

    /** Only a clear miss ends a pass; a projection a little over the goal usually settles by the end. */
    const val ABORT_RATIO = 1.5

    fun projectedBytes(fileBytes: Long, progress: Float): Long =
        if (progress <= 0f) 0L else Math.round(fileBytes / progress.toDouble())

    /** The projected final size when the pass should stop now, else null. */
    fun abortWith(fileBytes: Long, progress: Float, goalBytes: Long): Long? {
        if (progress < MIN_PROGRESS || progress >= 1f || fileBytes <= 0 || goalBytes <= 0) return null
        val projected = projectedBytes(fileBytes, progress)
        return projected.takeIf { it > goalBytes * ABORT_RATIO }
    }
}
