package me.manga.yamiapk.di.firebase

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.messaging.FirebaseMessaging
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object FirebaseModule {


    @Provides
    @Singleton
    fun provideFirebaseApp(@ApplicationContext context: Context): FirebaseApp {
        // initializeApp returns the default app; if already initialized, it just returns it
        return FirebaseApp.initializeApp(context)!!
    }

    @Provides @Singleton
    fun provideFirebaseFirestore(app: FirebaseApp): FirebaseFirestore =
        FirebaseFirestore.getInstance(app)




    @Provides
    @Singleton
    fun provideFirebaseAnalytics(
        @ApplicationContext context: Context
    ): FirebaseAnalytics {
        // Analytics needs the context
        return FirebaseAnalytics.getInstance(context)
    }

    @Provides
    @Singleton
    fun provideFirebaseMessaging(): FirebaseMessaging =
        FirebaseMessaging.getInstance()
}
