package com.example.aichallenge.server

interface ChatLocalServer {
    fun setRole(newRole: Role)
    fun getRole(): Role
}

