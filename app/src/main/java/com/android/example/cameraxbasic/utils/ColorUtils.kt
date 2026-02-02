package com.android.example.cameraxbasic.utils

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.max

object ColorUtils {

    /**
     * Inverts a 3x3 matrix.
     * Returns Identity if determinant is too small.
     */
    fun invert3x3(m: FloatArray): FloatArray {
        if (m.size != 9) return floatArrayOf(1f,0f,0f, 0f,1f,0f, 0f,0f,1f)

        val det = m[0] * (m[4] * m[8] - m[7] * m[5]) -
                  m[1] * (m[3] * m[8] - m[6] * m[5]) +
                  m[2] * (m[3] * m[7] - m[6] * m[4])

        if (abs(det) < 1e-6f) {
            // Singular matrix, return identity
            return floatArrayOf(1f,0f,0f, 0f,1f,0f, 0f,0f,1f)
        }

        val invDet = 1.0f / det
        val inv = FloatArray(9)

        inv[0] = (m[4] * m[8] - m[5] * m[7]) * invDet
        inv[1] = (m[2] * m[7] - m[1] * m[8]) * invDet
        inv[2] = (m[1] * m[5] - m[2] * m[4]) * invDet

        inv[3] = (m[5] * m[6] - m[3] * m[8]) * invDet
        inv[4] = (m[0] * m[8] - m[2] * m[6]) * invDet
        inv[5] = (m[2] * m[3] - m[0] * m[5]) * invDet

        inv[6] = (m[3] * m[7] - m[4] * m[6]) * invDet
        inv[7] = (m[1] * m[6] - m[0] * m[7]) * invDet
        inv[8] = (m[0] * m[4] - m[1] * m[3]) * invDet

        return inv
    }

    /**
     * Linearly interpolates between two 3x3 matrices.
     * result = (1 - weight) * m1 + weight * m2
     */
    fun interpolateMatrices(m1: FloatArray, m2: FloatArray, weight: Float): FloatArray {
        if (m1.size != 9 || m2.size != 9) return m1
        val res = FloatArray(9)
        for (i in 0 until 9) {
            res[i] = m1[i] * (1.0f - weight) + m2[i] * weight
        }
        return res
    }

    /**
     * Multiplies two 3x3 matrices (A * B).
     */
    fun multiplyMatrices(a: FloatArray, b: FloatArray): FloatArray {
        if (a.size != 9 || b.size != 9) return a
        val res = FloatArray(9)
        for (r in 0..2) {
            for (c in 0..2) {
                var sum = 0f
                for (k in 0..2) {
                    sum += a[r * 3 + k] * b[k * 3 + c]
                }
                res[r * 3 + c] = sum
            }
        }
        return res
    }

}
