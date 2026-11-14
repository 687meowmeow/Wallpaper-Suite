package com.wepwawet.engine

import android.net.Uri

/**
 * Stages .mpkg imports that arrive via ACTION_VIEW (file managers), for the
 * Library screen to consume when it becomes visible.
 */
object ImportRequestHandler {

    @Volatile
    private var stagedUri: Uri? = null

    fun stage(uri: Uri) {
        stagedUri = uri
    }

    /** Returns and clears the staged import (null when none pending). */
    fun consume(): Uri? {
        val uri = stagedUri
        stagedUri = null
        return uri
    }

    fun peek(): Uri? = stagedUri
}
