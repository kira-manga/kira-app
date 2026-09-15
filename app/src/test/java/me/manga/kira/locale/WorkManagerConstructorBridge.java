package me.manga.kira.locale;

import androidx.work.WorkManager;

/** Test-only access to Work 2.11.2's public JVM, Kotlin-internal, no-op constructor. */
public abstract class WorkManagerConstructorBridge extends WorkManager {
    protected WorkManagerConstructorBridge() {
        super();
    }
}
