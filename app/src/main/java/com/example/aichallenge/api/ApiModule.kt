package com.example.aichallenge.api

import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

object ApiModule {
    private val retrofit: Retrofit by lazy {
        Retrofit.Builder()
            .baseUrl("http://127.0.0.1:8080/")
            .addConverterFactory(GsonConverterFactory.create())
            .build()
    }

    val localApi: LocalApi by lazy { retrofit.create(LocalApi::class.java) }
}

