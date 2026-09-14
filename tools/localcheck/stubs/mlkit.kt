package com.google.android.gms.tasks

class Task<T> {
    fun addOnSuccessListener(listener: (T) -> Unit): Task<T> = this
    fun addOnFailureListener(listener: (Exception) -> Unit): Task<T> = this
}
