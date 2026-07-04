package me.manga.yamiapk.di.network

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import me.manga.yamiapk.data.remote.api.IMangaDataApiServices
import me.manga.yamiapk.di.app.MainOkHttpClient
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.scalars.ScalarsConverterFactory
import javax.inject.Singleton
import kotlin.jvm.java

@Module
@InstallIn(SingletonComponent::class)
object  NetworkModule {



    @Provides
    @Singleton
    fun provideRetrofitSMangaLek(
        @MainOkHttpClient okHttpClient: OkHttpClient    // 👈 Inject the custom client from AppModule
    ): Retrofit {
        return Retrofit.Builder()
            .baseUrl("https://placeholder.com/")
            .addConverterFactory(ScalarsConverterFactory.create())
            .client(okHttpClient)     // 👈 Use it here
            .build()
    }
    @Provides
    @Singleton
    fun provideMangaLekApiService(
        retrofit: Retrofit
    ): IMangaDataApiServices {
        return retrofit.create(IMangaDataApiServices::class.java)
    }




}