import math

def mat_mul(A, B):
    C = [[0,0,0],[0,0,0],[0,0,0]]
    for i in range(3):
        for j in range(3):
            for k in range(3):
                C[i][j] += A[i][k] * B[k][j]
    return C

def mat_vec_mul(A, v):
    res = [0,0,0]
    for i in range(3):
        for j in range(3):
            res[i] += A[i][j] * v[j]
    return res

def mat_inv(A):
    det = A[0][0]*(A[1][1]*A[2][2] - A[1][2]*A[2][1]) - \
          A[0][1]*(A[1][0]*A[2][2] - A[1][2]*A[2][0]) + \
          A[0][2]*(A[1][0]*A[2][1] - A[1][1]*A[2][0])

    if det == 0: return None
    invDet = 1.0 / det

    res = [[0,0,0],[0,0,0],[0,0,0]]
    res[0][0] = (A[1][1]*A[2][2] - A[1][2]*A[2][1]) * invDet
    res[0][1] = (A[0][2]*A[2][1] - A[0][1]*A[2][2]) * invDet
    res[0][2] = (A[0][1]*A[1][2] - A[0][2]*A[1][1]) * invDet
    res[1][0] = (A[1][2]*A[2][0] - A[1][0]*A[2][2]) * invDet
    res[1][1] = (A[0][0]*A[2][2] - A[0][2]*A[2][0]) * invDet
    res[1][2] = (A[0][2]*A[1][0] - A[0][0]*A[1][2]) * invDet
    res[2][0] = (A[1][0]*A[2][1] - A[1][1]*A[2][0]) * invDet
    res[2][1] = (A[0][1]*A[2][0] - A[0][0]*A[2][1]) * invDet
    res[2][2] = (A[0][0]*A[1][1] - A[0][1]*A[1][0]) * invDet
    return res

def get_xyz_from_xy(x, y):
    if y == 0: return [0,0,0]
    return [x/y, 1.0, (1-x-y)/y]

def get_matrix_rgb_to_xyz(primaries, white_point):
    xr, yr = primaries['r']
    xg, yg = primaries['g']
    xb, yb = primaries['b']
    xw, yw = white_point

    Xr, Yr, Zr = get_xyz_from_xy(xr, yr)
    Xg, Yg, Zg = get_xyz_from_xy(xg, yg)
    Xb, Yb, Zb = get_xyz_from_xy(xb, yb)
    Xw, Yw, Zw = get_xyz_from_xy(xw, yw)

    M_p = [
        [Xr, Xg, Xb],
        [Yr, Yg, Yb],
        [Zr, Zg, Zb]
    ]

    M_p_inv = mat_inv(M_p)
    W = [Xw, Yw, Zw]
    S = mat_vec_mul(M_p_inv, W)

    M = [
        [M_p[0][0]*S[0], M_p[0][1]*S[1], M_p[0][2]*S[2]],
        [M_p[1][0]*S[0], M_p[1][1]*S[1], M_p[1][2]*S[2]],
        [M_p[2][0]*S[0], M_p[2][1]*S[1], M_p[2][2]*S[2]]
    ]
    return M

# Primaries & White Points
primaries_prophoto = {'r': (0.7347, 0.2653), 'g': (0.1596, 0.8404), 'b': (0.0366, 0.0001)}
wp_d50 = (0.34567, 0.35850)

# Calculate M_ProPhoto_to_XYZ (D50)
M_ProPhoto_to_XYZ = get_matrix_rgb_to_xyz(primaries_prophoto, wp_d50)

# Invert to get M_XYZ_to_ProPhoto
M_XYZ_to_ProPhoto = mat_inv(M_ProPhoto_to_XYZ)

print("Calculated M_XYZ_D50_to_ProPhoto:")
for row in M_XYZ_to_ProPhoto:
    print([f"{x:.8f}" for x in row])

# Current values in ColorMatrices.h
current_M = [
    [1.34595631, -0.25560998, -0.05111226],
    [-0.54459674, 1.50816141, 0.02053506],
    [0.00000000, 0.00000000, 1.21184464]
]

print("\nCurrent M_XYZ_D50_to_ProPhoto in Code:")
for row in current_M:
    print([f"{x:.8f}" for x in row])

# Verify Output mapping
# Red Input (X=high, Y=low) -> Output?
# Row 0: Red Out. 1.34*X - 0.25*Y. Positive. Correct.
# Row 1: Green Out. -0.54*X + 1.50*Y. Negative for pure X. Correct.

# Conclusion on Swap:
# If X and Y are swapped in input vector (G, R, B instead of R, G, B)?
# Then Input X is Green, Input Y is Red.
# Out Red = 1.34*Green - 0.25*Red. (Greenish Red?)
# Out Green = -0.54*Green + 1.50*Red. (Reddish Green?)
# This matches User Description: "Red Cup -> Green".
# So the Input to M_XYZ_to_ProPhoto is likely Swapped (Y, X, Z).

# How can input be swapped?
# M_Sensor_to_Pro = M_XYZ_to_Pro * CCM.
# Input = Sensor data.
# CCM maps Sensor -> XYZ.
# If CCM produces (Y, X, Z) instead of (X, Y, Z)?
# Unlikely standard Android behavior.

# Wait. What if CCM is Column-Major in memory, but read as Row-Major?
# A = [[a, b, c], [d, e, f], [g, h, i]]
# Transpose(A) = [[a, d, g], [b, e, h], [c, f, i]]
# If A maps R->X, G->Y (Diagonal dominant).
# a \approx 1, e \approx 1.
# Transpose has a \approx 1, e \approx 1.
# Diagonal doesn't swap much.
# So Transpose is unlikely to cause full Red/Green swap.
