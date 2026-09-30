#include "RcdDemosaic.h"
#include <omp.h>
#include <algorithm>
#include <cmath>
#include <vector>

namespace darkbag {
namespace demosaic {

// Helper to determine CFA color at given coordinate
// 0=R, 1=G, 2=B
inline int fc(int cfa_pattern, int r, int c) {
    if (cfa_pattern == 0) { // RGGB
        if (r % 2 == 0) return (c % 2 == 0) ? 0 : 1;
        else            return (c % 2 == 0) ? 1 : 2;
    } else if (cfa_pattern == 1) { // GRBG
        if (r % 2 == 0) return (c % 2 == 0) ? 1 : 0;
        else            return (c % 2 == 0) ? 2 : 1;
    } else if (cfa_pattern == 2) { // GBRG
        if (r % 2 == 0) return (c % 2 == 0) ? 1 : 2;
        else            return (c % 2 == 0) ? 0 : 1;
    } else { // BGGR
        if (r % 2 == 0) return (c % 2 == 0) ? 2 : 1;
        else            return (c % 2 == 0) ? 1 : 0;
    }
}

inline float SQR(float x) {
    return x * x;
}

inline float LIM01(float x) {
    return std::clamp(x, 0.0f, 1.0f);
}

inline float intp(float d, float x, float y) {
    return d * x + (1.0f - d) * y;
}

void ppg_fallback(const float* cfa, float* rgb0, float* rgb1, float* rgb2, int tilecols, int tileRows, int stride, int cfa_pattern, int border, int rowStart, int colStart) {
    for (int r = 1; r < tileRows - 1; ++r) {
        for (int c = 1; c < tilecols - 1; ++c) {
            // Only apply to the 9-pixel border since RCD computes the center
            if (r >= border && r < tileRows - border && c >= border && c < tilecols - border) {
                continue;
            }
            
            int color = fc(cfa_pattern, r + rowStart, c + colStart);
            int idx = r * stride + c;
            
            if (color == 0) { // R is known
                rgb1[idx] = (cfa[r * stride + c - 1] + cfa[r * stride + c + 1] + cfa[(r - 1) * stride + c] + cfa[(r + 1) * stride + c]) * 0.25f;
                rgb2[idx] = (cfa[(r - 1) * stride + c - 1] + cfa[(r - 1) * stride + c + 1] + cfa[(r + 1) * stride + c - 1] + cfa[(r + 1) * stride + c + 1]) * 0.25f;
            } else if (color == 1) { // G is known
                if (fc(cfa_pattern, r + rowStart, c + colStart - 1) == 0) { // R horizontally
                    rgb0[idx] = (cfa[r * stride + c - 1] + cfa[r * stride + c + 1]) * 0.5f;
                    rgb2[idx] = (cfa[(r - 1) * stride + c] + cfa[(r + 1) * stride + c]) * 0.5f;
                } else { // B horizontally
                    rgb2[idx] = (cfa[r * stride + c - 1] + cfa[r * stride + c + 1]) * 0.5f;
                    rgb0[idx] = (cfa[(r - 1) * stride + c] + cfa[(r + 1) * stride + c]) * 0.5f;
                }
            } else { // B is known
                rgb1[idx] = (cfa[r * stride + c - 1] + cfa[r * stride + c + 1] + cfa[(r - 1) * stride + c] + cfa[(r + 1) * stride + c]) * 0.25f;
                rgb0[idx] = (cfa[(r - 1) * stride + c - 1] + cfa[(r - 1) * stride + c + 1] + cfa[(r + 1) * stride + c - 1] + cfa[(r + 1) * stride + c + 1]) * 0.25f;
            }
        }
    }

    // Replicate 1-pixel outer boundary to avoid uninitialized values
    for (int c = 0; c < tilecols; ++c) {
        rgb0[c] = rgb0[stride + c];
        rgb1[c] = rgb1[stride + c];
        rgb2[c] = rgb2[stride + c];
        if (tileRows > 1) {
            int lastRowIdx = (tileRows - 1) * stride + c;
            int prevRowIdx = (tileRows - 2) * stride + c;
            rgb0[lastRowIdx] = rgb0[prevRowIdx];
            rgb1[lastRowIdx] = rgb1[prevRowIdx];
            rgb2[lastRowIdx] = rgb2[prevRowIdx];
        }
    }
    for (int r = 0; r < tileRows; ++r) {
        rgb0[r * stride] = rgb0[r * stride + 1];
        rgb1[r * stride] = rgb1[r * stride + 1];
        rgb2[r * stride] = rgb2[r * stride + 1];
        if (tilecols > 1) {
            int lastColIdx = r * stride + tilecols - 1;
            int prevColIdx = r * stride + tilecols - 2;
            rgb0[lastColIdx] = rgb0[prevColIdx];
            rgb1[lastColIdx] = rgb1[prevColIdx];
            rgb2[lastColIdx] = rgb2[prevColIdx];
        }
    }
}

void rcd_demosaic(const uint16_t* bayer_input,
                  int width,
                  int height,
                  int cfa_pattern,
                  const uint16_t* black_level,
                  uint16_t white_level,
                  const float* white_balance,
                  uint16_t* rgb_output)
{
    constexpr int tileBorder = 9;
    constexpr int rcdBorder = 9;
    constexpr int tileSize = 194;
    constexpr int tileSizeN = tileSize - 2 * tileBorder;
    const int numTh = height / tileSizeN + ((height % tileSizeN) ? 1 : 0);
    const int numTw = width / tileSizeN + ((width % tileSizeN) ? 1 : 0);
    
    constexpr int w1 = tileSize;
    constexpr int w2 = 2 * tileSize;
    constexpr int w3 = 3 * tileSize;
    constexpr int w4 = 4 * tileSize;
    
    constexpr float eps = 1e-5f;
    constexpr float epssq = 1e-10f;

    const float wb_r  = white_balance ? white_balance[0] : 1.0f;
    const float wb_g0 = white_balance ? white_balance[1] : 1.0f;
    const float wb_g1 = white_balance ? white_balance[2] : 1.0f;
    const float wb_b  = white_balance ? white_balance[3] : 1.0f;
    
    #pragma omp parallel
    {
        std::vector<float> cfa(tileSize * tileSize);
        std::vector<float> rgb0(tileSize * tileSize); // R
        std::vector<float> rgb1(tileSize * tileSize); // G
        std::vector<float> rgb2(tileSize * tileSize); // B
        
        std::vector<float> VH_Dir(tileSize * tileSize);
        std::vector<float> PQ_Dir(tileSize * tileSize / 2);
        std::vector<float> P_CDiff_Hpf(tileSize * tileSize / 2);
        std::vector<float> Q_CDiff_Hpf(tileSize * tileSize / 2);
        
        float* rgb[3] = {rgb0.data(), rgb1.data(), rgb2.data()};
        float* lpf = PQ_Dir.data(); // reuse buffer as they don't overlap in usage

        #pragma omp for schedule(dynamic) collapse(2)
        for(int tr = 0; tr < numTh; ++tr) {
            for(int tc = 0; tc < numTw; ++tc) {
                const int rowStart = tr * tileSizeN;
                const int rowEnd = std::min(rowStart + tileSize, height);
                if(rowStart + rcdBorder == rowEnd - rcdBorder) {
                    continue;
                }
                const int colStart = tc * tileSizeN;
                const int colEnd = std::min(colStart + tileSize, width);
                if(colStart + rcdBorder == colEnd - rcdBorder) {
                    continue;
                }

                const int tileRows = std::min(rowEnd - rowStart, tileSize);
                const int tilecols = std::min(colEnd - colStart, tileSize);

                for (int row = rowStart; row < rowEnd; row++) {
                    for (int col = colStart, indx = (row - rowStart) * tileSize; col < colEnd; ++col, ++indx) {
                        int c_color = fc(cfa_pattern, row, col);
                        float bl;
                        float wb;
                        if (c_color == 0) {
                            bl = black_level[0];
                            wb = wb_r;
                        } else if (c_color == 1) {
                            bl = (row % 2 == 0) ? black_level[1] : black_level[2];
                            wb = (row % 2 == 0) ? wb_g0 : wb_g1;
                        } else {
                            bl = black_level[3];
                            wb = wb_b;
                        }
                        float val = static_cast<float>(bayer_input[row * width + col]);
                        float denom = std::max(1.0f, static_cast<float>(white_level) - bl);
                        val = LIM01(((val - bl) / denom) * wb);
                        
                        cfa[indx] = val;
                        rgb0[indx] = val;
                        rgb1[indx] = val;
                        rgb2[indx] = val;
                    }
                }

                // Step 1: Find cardinal and diagonal interpolation directions
                float bufferV[3][tileSize - 8];

                // Step 1.1: Calculate the square of the vertical and horizontal color difference high pass filter
                for (int row = 3; row < std::min(tileRows - 3, 5); ++row) {
                    for (int col = 4, indx = row * tileSize + col; col < tilecols - 4; ++col, ++indx) {
                        bufferV[row - 3][col - 4] = SQR((cfa[indx - w3] - cfa[indx - w1] - cfa[indx + w1] + cfa[indx + w3]) - 3.f * (cfa[indx - w2] + cfa[indx + w2])  + 6.f * cfa[indx]);
                    }
                }

                // Step 1.2: Obtain the vertical and horizontal directional discrimination strength
                float bufferH[tileSize - 6];
                float* V0 = bufferV[0];
                float* V1 = bufferV[1];
                float* V2 = bufferV[2];
                for (int row = 4; row < tileRows - 4; ++row) {
                    for (int col = 3, indx = row * tileSize + col; col < tilecols - 3; ++col, ++indx) {
                        bufferH[col - 3] = SQR((cfa[indx -  3] - cfa[indx -  1] - cfa[indx +  1] + cfa[indx +  3]) - 3.f * (cfa[indx -  2] + cfa[indx +  2]) + 6.f * cfa[indx]);
                    }
                    for (int col = 4, indx = (row + 1) * tileSize + col; col < tilecols - 4; ++col, ++indx) {
                        V2[col - 4] = SQR((cfa[indx - w3] - cfa[indx - w1] - cfa[indx + w1] + cfa[indx + w3]) - 3.f * (cfa[indx - w2] + cfa[indx + w2])  + 6.f * cfa[indx]);
                    }
                    for (int col = 4, indx = row * tileSize + col; col < tilecols - 4; ++col, ++indx) {
                        float V_Stat = std::max(epssq, V0[col - 4] + V1[col - 4] + V2[col - 4]);
                        float H_Stat = std::max(epssq, bufferH[col -  4] + bufferH[col - 3] + bufferH[col -  2]);
                        VH_Dir[indx] = V_Stat / (V_Stat + H_Stat);
                    }
                    std::swap(V0, V2);
                    std::swap(V0, V1);
                }

                // Step 2: Low pass filter incorporating green, red and blue local samples from the raw data
                for (int row = 2; row < tileRows - 2; ++row) {
                    for (int col = 2 + (fc(cfa_pattern, row + rowStart, colStart) & 1), indx = row * tileSize + col, lpindx = indx / 2; col < tilecols - 2; col += 2, indx += 2, ++lpindx) {
                        lpf[lpindx] = cfa[indx] +
                                      0.5f * (cfa[indx - w1] + cfa[indx + w1] + cfa[indx - 1] + cfa[indx + 1]) +
                                      0.25f * (cfa[indx - w1 - 1] + cfa[indx - w1 + 1] + cfa[indx + w1 - 1] + cfa[indx + w1 + 1]);
                    }
                }

                // Step 3: Populate the green channel at blue and red CFA positions
                for (int row = 4; row < tileRows - 4; ++row) {
                    for (int col = 4 + (fc(cfa_pattern, row + rowStart, colStart) & 1), indx = row * tileSize + col, lpindx = indx / 2; col < tilecols - 4; col += 2, indx += 2, ++lpindx) {
                        const float cfai = cfa[indx];
                        const float N_Grad = eps + (std::fabs(cfa[indx - w1] - cfa[indx + w1]) + std::fabs(cfai - cfa[indx - w2])) + (std::fabs(cfa[indx - w1] - cfa[indx - w3]) + std::fabs(cfa[indx - w2] - cfa[indx - w4]));
                        const float S_Grad = eps + (std::fabs(cfa[indx - w1] - cfa[indx + w1]) + std::fabs(cfai - cfa[indx + w2])) + (std::fabs(cfa[indx + w1] - cfa[indx + w3]) + std::fabs(cfa[indx + w2] - cfa[indx + w4]));
                        const float W_Grad = eps + (std::fabs(cfa[indx -  1] - cfa[indx +  1]) + std::fabs(cfai - cfa[indx -  2])) + (std::fabs(cfa[indx -  1] - cfa[indx -  3]) + std::fabs(cfa[indx -  2] - cfa[indx -  4]));
                        const float E_Grad = eps + (std::fabs(cfa[indx -  1] - cfa[indx +  1]) + std::fabs(cfai - cfa[indx +  2])) + (std::fabs(cfa[indx +  1] - cfa[indx +  3]) + std::fabs(cfa[indx +  2] - cfa[indx +  4]));

                        const float lpfi = lpf[lpindx];
                        const float N_Est = cfa[indx - w1] * (lpfi + lpfi) / (eps + lpfi + lpf[lpindx - w1 / 2]);
                        const float S_Est = cfa[indx + w1] * (lpfi + lpfi) / (eps + lpfi + lpf[lpindx + w1 / 2]);
                        const float W_Est = cfa[indx -  1] * (lpfi + lpfi) / (eps + lpfi + lpf[lpindx -  1]);
                        const float E_Est = cfa[indx +  1] * (lpfi + lpfi) / (eps + lpfi + lpf[lpindx +  1]);

                        const float V_Est = (S_Grad * N_Est + N_Grad * S_Est) / (N_Grad + S_Grad);
                        const float H_Est = (W_Grad * E_Est + E_Grad * W_Est) / (E_Grad + W_Grad);

                        const float VH_Central_Value = VH_Dir[indx];
                        const float VH_Neighbourhood_Value = 0.25f * ((VH_Dir[indx - w1 - 1] + VH_Dir[indx - w1 + 1]) + (VH_Dir[indx + w1 - 1] + VH_Dir[indx + w1 + 1]));

                        const float VH_Disc = std::fabs(0.5f - VH_Central_Value) < std::fabs(0.5f - VH_Neighbourhood_Value) ? VH_Neighbourhood_Value : VH_Central_Value;
                        rgb[1][indx] = intp(VH_Disc, H_Est, V_Est);
                    }
                }

                // Step 4: Populate the red and blue channels
                // Step 4.0: Calculate the square of the P/Q diagonals color difference high pass filter
                for (int row = 3; row < tileRows - 3; ++row) {
                    for (int col = 3, indx = row * tileSize + col, indx2 = indx / 2; col < tilecols - 3; col+=2, indx+=2, indx2++ ) {
                        P_CDiff_Hpf[indx2] = SQR((cfa[indx - w3 - 3] - cfa[indx - w1 - 1] - cfa[indx + w1 + 1] + cfa[indx + w3 + 3]) - 3.f * (cfa[indx - w2 - 2] + cfa[indx + w2 + 2]) + 6.f * cfa[indx]);
                        Q_CDiff_Hpf[indx2] = SQR((cfa[indx - w3 + 3] - cfa[indx - w1 + 1] - cfa[indx + w1 - 1] + cfa[indx + w3 - 3]) - 3.f * (cfa[indx - w2 + 2] + cfa[indx + w2 - 2]) + 6.f * cfa[indx]);
                    }
                }

                // Step 4.1: Obtain the P/Q diagonals directional discrimination strength
                for (int row = 4; row < tileRows - 4; ++row) {
                    for (int col = 4 + (fc(cfa_pattern, row + rowStart, colStart) & 1), indx = row * tileSize + col, indx2 = indx / 2, indx3 = (indx - w1 - 1) / 2, indx4 = (indx + w1 - 1) / 2; col < tilecols - 4; col += 2, indx += 2, indx2++, indx3++, indx4++ ) {
                        float P_Stat = std::max(epssq, P_CDiff_Hpf[indx3] + P_CDiff_Hpf[indx2] + P_CDiff_Hpf[indx4 + 1]);
                        float Q_Stat = std::max(epssq, Q_CDiff_Hpf[indx3 + 1] + Q_CDiff_Hpf[indx2] + Q_CDiff_Hpf[indx4]);
                        PQ_Dir[indx2] = P_Stat / (P_Stat + Q_Stat);
                    }
                }

                // Step 4.2: Populate the red and blue channels at blue and red CFA positions
                for (int row = 4; row < tileRows - 4; ++row) {
                    for (int col = 4 + (fc(cfa_pattern, row + rowStart, colStart) & 1), indx = row * tileSize + col, c = 2 - fc(cfa_pattern, row + rowStart, col + colStart), pqindx = indx / 2, pqindx2 = (indx - w1 - 1) / 2, pqindx3 = (indx + w1 - 1) / 2; col < tilecols - 4; col += 2, indx += 2, ++pqindx, ++pqindx2, ++pqindx3) {
                        float PQ_Central_Value   = PQ_Dir[pqindx];
                        float PQ_Neighbourhood_Value = 0.25f * (PQ_Dir[pqindx2] + PQ_Dir[pqindx2 + 1] + PQ_Dir[pqindx3] + PQ_Dir[pqindx3 + 1]);

                        float PQ_Disc = (std::fabs(0.5f - PQ_Central_Value) < std::fabs(0.5f - PQ_Neighbourhood_Value)) ? PQ_Neighbourhood_Value : PQ_Central_Value;

                        float NW_Grad = eps + std::fabs(rgb[c][indx - w1 - 1] - rgb[c][indx + w1 + 1]) + std::fabs(rgb[c][indx - w1 - 1] - rgb[c][indx - w3 - 3]) + std::fabs(rgb[1][indx] - rgb[1][indx - w2 - 2]);
                        float NE_Grad = eps + std::fabs(rgb[c][indx - w1 + 1] - rgb[c][indx + w1 - 1]) + std::fabs(rgb[c][indx - w1 + 1] - rgb[c][indx - w3 + 3]) + std::fabs(rgb[1][indx] - rgb[1][indx - w2 + 2]);
                        float SW_Grad = eps + std::fabs(rgb[c][indx - w1 + 1] - rgb[c][indx + w1 - 1]) + std::fabs(rgb[c][indx + w1 - 1] - rgb[c][indx + w3 - 3]) + std::fabs(rgb[1][indx] - rgb[1][indx + w2 - 2]);
                        float SE_Grad = eps + std::fabs(rgb[c][indx - w1 - 1] - rgb[c][indx + w1 + 1]) + std::fabs(rgb[c][indx + w1 + 1] - rgb[c][indx + w3 + 3]) + std::fabs(rgb[1][indx] - rgb[1][indx + w2 + 2]);

                        float NW_Est = rgb[c][indx - w1 - 1] - rgb[1][indx - w1 - 1];
                        float NE_Est = rgb[c][indx - w1 + 1] - rgb[1][indx - w1 + 1];
                        float SW_Est = rgb[c][indx + w1 - 1] - rgb[1][indx + w1 - 1];
                        float SE_Est = rgb[c][indx + w1 + 1] - rgb[1][indx + w1 + 1];

                        float P_Est = (NW_Grad * SE_Est + SE_Grad * NW_Est) / (NW_Grad + SE_Grad);
                        float Q_Est = (NE_Grad * SW_Est + SW_Grad * NE_Est) / (NE_Grad + SW_Grad);

                        rgb[c][indx] = rgb[1][indx] + intp(PQ_Disc, Q_Est, P_Est);
                    }
                }

                // Step 4.3: Populate the red and blue channels at green CFA positions
                for (int row = 4; row < tileRows - 4; ++row) {
                    for (int col = 4 + (fc(cfa_pattern, row + rowStart, colStart + 1) & 1), indx = row * tileSize + col; col < tilecols - 4; col += 2, indx += 2) {
                        float VH_Central_Value = VH_Dir[indx];
                        float VH_Neighbourhood_Value = 0.25f * ((VH_Dir[indx - w1 - 1] + VH_Dir[indx - w1 + 1]) + (VH_Dir[indx + w1 - 1] + VH_Dir[indx + w1 + 1]));

                        float VH_Disc = (std::fabs(0.5f - VH_Central_Value) < std::fabs(0.5f - VH_Neighbourhood_Value)) ? VH_Neighbourhood_Value : VH_Central_Value;
                        float rgb1 = rgb[1][indx];
                        float N1 = eps + std::fabs(rgb1 - rgb[1][indx - w2]);
                        float S1 = eps + std::fabs(rgb1 - rgb[1][indx + w2]);
                        float W1 = eps + std::fabs(rgb1 - rgb[1][indx -  2]);
                        float E1 = eps + std::fabs(rgb1 - rgb[1][indx +  2]);

                        float rgb1mw1 = rgb[1][indx - w1];
                        float rgb1pw1 = rgb[1][indx + w1];
                        float rgb1m1 = rgb[1][indx - 1];
                        float rgb1p1 = rgb[1][indx + 1];
                        for (int c = 0; c <= 2; c += 2) {
                            float SNabs = std::fabs(rgb[c][indx - w1] - rgb[c][indx + w1]);
                            float EWabs = std::fabs(rgb[c][indx -  1] - rgb[c][indx +  1]);
                            float N_Grad = N1 + SNabs + std::fabs(rgb[c][indx - w1] - rgb[c][indx - w3]);
                            float S_Grad = S1 + SNabs + std::fabs(rgb[c][indx + w1] - rgb[c][indx + w3]);
                            float W_Grad = W1 + EWabs + std::fabs(rgb[c][indx -  1] - rgb[c][indx -  3]);
                            float E_Grad = E1 + EWabs + std::fabs(rgb[c][indx +  1] - rgb[c][indx +  3]);

                            float N_Est = rgb[c][indx - w1] - rgb1mw1;
                            float S_Est = rgb[c][indx + w1] - rgb1pw1;
                            float W_Est = rgb[c][indx -  1] - rgb1m1;
                            float E_Est = rgb[c][indx +  1] - rgb1p1;

                            float V_Est = (N_Grad * S_Est + S_Grad * N_Est) / (N_Grad + S_Grad);
                            float H_Est = (E_Grad * W_Est + W_Grad * E_Est) / (E_Grad + W_Grad);

                            rgb[c][indx] = rgb1 + intp(VH_Disc, H_Est, V_Est);
                        }
                    }
                }

                // Apply PPG fallback on the missing 9px borders (using tileSize as stride)
                ppg_fallback(cfa.data(), rgb0.data(), rgb1.data(), rgb2.data(), tilecols, tileRows, tileSize, cfa_pattern, tileBorder, rowStart, colStart);

                const int firstVertical = rowStart + ((tr == 0) ? 0 : tileBorder);
                const int lastVertical = rowEnd - ((tr == numTh - 1) ? 0 : tileBorder);
                const int firstHorizontal = colStart + ((tc == 0) ? 0 : tileBorder);
                const int lastHorizontal =  colEnd - ((tc == numTw - 1) ? 0 : tileBorder);
                
                const size_t plane_stride = static_cast<size_t>(width) * height;
                for (int row = firstVertical; row < lastVertical; ++row) {
                    for (int col = firstHorizontal; col < lastHorizontal; ++col) {
                        int idx = (row - rowStart) * tileSize + col - colStart;
                        size_t out_idx = static_cast<size_t>(row) * width + col;
                        rgb_output[out_idx] = static_cast<uint16_t>(std::clamp(rgb0[idx] * 65535.0f, 0.0f, 65535.0f));
                        rgb_output[out_idx + plane_stride] = static_cast<uint16_t>(std::clamp(rgb1[idx] * 65535.0f, 0.0f, 65535.0f));
                        rgb_output[out_idx + 2 * plane_stride] = static_cast<uint16_t>(std::clamp(rgb2[idx] * 65535.0f, 0.0f, 65535.0f));
                    }
                }
            }
        }
    }
}

} // namespace demosaic
} // namespace darkbag
