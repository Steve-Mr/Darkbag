import math

def mat_mul(A, B):
    C = [[0, 0, 0], [0, 0, 0], [0, 0, 0]]
    for i in range(3):
        for j in range(3):
            for k in range(3):
                C[i][j] += A[i][k] * B[k][j]
    return C

def mat_inv(m):
    det = m[0][0] * (m[1][1] * m[2][2] - m[2][1] * m[1][2]) -           m[0][1] * (m[1][0] * m[2][2] - m[1][2] * m[2][0]) +           m[0][2] * (m[1][0] * m[2][1] - m[1][1] * m[2][0])
    invDet = 1.0 / det
    minv = [[0, 0, 0], [0, 0, 0], [0, 0, 0]]
    minv[0][0] = (m[1][1] * m[2][2] - m[2][1] * m[1][2]) * invDet
    minv[0][1] = (m[0][2] * m[2][1] - m[0][1] * m[2][2]) * invDet
    minv[0][2] = (m[0][1] * m[1][2] - m[0][2] * m[1][1]) * invDet
    minv[1][0] = (m[1][2] * m[2][0] - m[1][0] * m[2][2]) * invDet
    minv[1][1] = (m[0][0] * m[2][2] - m[0][2] * m[2][0]) * invDet
    minv[1][2] = (m[1][0] * m[0][2] - m[0][0] * m[1][2]) * invDet
    minv[2][0] = (m[1][0] * m[2][1] - m[2][0] * m[1][1]) * invDet
    minv[2][1] = (m[2][0] * m[0][1] - m[0][0] * m[2][1]) * invDet
    minv[2][2] = (m[0][0] * m[1][1] - m[1][0] * m[0][1]) * invDet
    return minv

def mat_vec_mul(m, v):
    res = [0, 0, 0]
    for i in range(3):
        for j in range(3):
            res[i] += m[i][j] * v[j]
    return res

# --- Constants ---

# White Points (ASTM E308-01)
XYZ_D50 = [0.96422, 1.00000, 0.82521]
XYZ_D65 = [0.95047, 1.00000, 1.08883]

# Bradford Matrices
M_BFD = [
    [0.8951000, 0.2664000, -0.1614000],
    [-0.7502000, 1.7135000, 0.0367000],
    [0.0389000, -0.0685000, 1.0296000]
]
M_BFD_INV = [
    [0.9869929, -0.1470543, 0.1599627],
    [0.4323053, 0.5183603, 0.0492912],
    [-0.0085287, 0.0400428, 0.9684867]
]

# --- 1. Calculate Adaptation Matrix (D50 -> D65) ---

src_lms = mat_vec_mul(M_BFD, XYZ_D50)
dst_lms = mat_vec_mul(M_BFD, XYZ_D65)

# Gain Matrix
gains = [
    [dst_lms[0]/src_lms[0], 0, 0],
    [0, dst_lms[1]/src_lms[1], 0],
    [0, 0, dst_lms[2]/src_lms[2]]
]

# M_adapt = M_BFD_INV * Gains * M_BFD
tmp = mat_mul(gains, M_BFD)
M_ADAPT_D50_D65 = mat_mul(M_BFD_INV, tmp)

print("Adaptation Matrix (D50 -> D65):")
for row in M_ADAPT_D50_D65: print(row)
print("-" * 20)

# --- 2. Calculate sRGB Matrix (XYZ D50 -> sRGB Linear) ---

# XYZ(D65) to sRGB Linear (Standard)
# From http://www.brucelindbloom.com/index.html?Eqn_RGB_XYZ_Matrix.html
XYZ_TO_SRGB_D65 = [
    [3.2404542, -1.5371385, -0.4985314],
    [-0.9692660, 1.8760108, 0.0415560],
    [0.0556434, -0.2040259, 1.0572252]
]

# Final = XYZ_TO_SRGB_D65 * M_ADAPT_D50_D65
FINAL_SRGB = mat_mul(XYZ_TO_SRGB_D65, M_ADAPT_D50_D65)

print("Final Matrix: XYZ(D50) -> sRGB(Linear):")
for row in FINAL_SRGB: print(f"{row[0]:.9f}, {row[1]:.9f}, {row[2]:.9f},")
print("-" * 20)

# User's Matrix for comparison
# 3.1338561, -1.6168667, -0.4906146
# -0.9787684, 1.9161415, 0.0334540
# 0.0719453, -0.2289914, 1.4052427

# --- 3. Calculate Rec.2020 Matrix (XYZ D50 -> Rec.2020 Linear) ---

# XYZ(D65) to Rec.2020 Linear
# From http://www.brucelindbloom.com/index.html?Eqn_RGB_XYZ_Matrix.html
XYZ_TO_REC2020_D65 = [
    [1.7166511, -0.3556707, -0.2533662],
    [-0.6666843, 1.6164812, 0.0157685],
    [0.0176398, -0.0427706, 0.9421031]
]

# Final = XYZ_TO_REC2020_D65 * M_ADAPT_D50_D65
FINAL_REC2020 = mat_mul(XYZ_TO_REC2020_D65, M_ADAPT_D50_D65)

print("Final Matrix: XYZ(D50) -> Rec.2020(Linear):")
for row in FINAL_REC2020: print(f"{row[0]:.9f}, {row[1]:.9f}, {row[2]:.9f},")

# Current Matrix in Code for comparison
# 1.64727856, -0.39359694, -0.23598106
# -0.68261476, 1.64760981, 0.01281589
# 0.02966404, -0.06291913, 1.25343115
