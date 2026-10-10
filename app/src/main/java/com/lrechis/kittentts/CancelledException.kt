package com.lrechis.kittentts

import java.io.IOException

/** Thrown when the user cancels a download or extraction that is in progress. */
class CancelledException : IOException("Cancelled")
