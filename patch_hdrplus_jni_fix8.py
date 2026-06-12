with open('app/src/main/cpp/HdrPlusJNI.cpp', 'r') as f:
    content = f.read()

import re

# We need to add normalization scale to the MHC algorithm.
# float norm_scale = 65535.0f / (whiteLevel - (bl_r + bl_g0 + bl_g1 + bl_b) / 4.0f); // Or just pick one black level if they are similar.
# In the original Halide pipeline:
# The pipeline subtracts black level and then normalizes by (whiteLevel - blackLevel) to 0.0-1.0, then scales to 65535.
# Let's fix this.
# Also fix the edge artifact by copying nearest neighbor.

# Current code in HdrPlusJNI.cpp:
# float val = std::max(0.0f, val - bl) * wb;
# We need to change this to:
# float norm_scale = 65535.0f / std::max(1.0f, (float)whiteLevel - bl);
# float val = std::max(0.0f, val - bl) * norm_scale * wb;

# Let's see the current code in the file:
# float val = (float)bayerData[idx];
# float bl = (c == 0) ? bl_r : (c == 2) ? bl_b : (x%2 == y%2) ? bl_g0 : bl_g1;
# float wb = (c == 0) ? wb_r : (c == 2) ? wb_b : (x%2 == y%2) ? wb_g0 : wb_g1;
# val = std::max(0.0f, val - bl) * wb;

# And for the edge copying:
# if (x < 2 || x >= width - 2 || y < 2 || y >= height - 2) {
#     finalImage[out_idx + 0] = (c == 0) ? std::min(65535.0f, val) : 0;
#     finalImage[out_idx + 1] = (c == 1) ? std::min(65535.0f, val) : 0;
#     finalImage[out_idx + 2] = (c == 2) ? std::min(65535.0f, val) : 0;
#     continue; // Skip full demosaic for edges to prevent out of bounds
# }

replacement = """
                float val = (float)bayerData[idx];
                float bl = (c == 0) ? bl_r : (c == 2) ? bl_b : (x%2 == y%2) ? bl_g0 : bl_g1;
                float wb = (c == 0) ? wb_r : (c == 2) ? wb_b : (x%2 == y%2) ? wb_g0 : wb_g1;

                float norm_scale = 65535.0f / std::max(1.0f, (float)whiteLevel - bl);
                val = std::max(0.0f, val - bl) * norm_scale * wb;
"""

content = re.sub(
    r'float val = \(float\)bayerData\[idx\];\s*float bl = \(c == 0\) \? bl_r : \(c == 2\) \? bl_b : \(x%2 == y%2\) \? bl_g0 : bl_g1;\s*float wb = \(c == 0\) \? wb_r : \(c == 2\) \? wb_b : \(x%2 == y%2\) \? wb_g0 : wb_g1;\s*val = std::max\(0\.0f, val - bl\) \* wb;',
    replacement,
    content
)

replacement2 = """
                    float p_val = (float)bayerData[(y + dy) * width + (x + dx)];
                    float p_bl = (p_c == 0) ? bl_r : (p_c == 2) ? bl_b : ((x+dx)%2 == (y+dy)%2) ? bl_g0 : bl_g1;
                    float p_wb = (p_c == 0) ? wb_r : (p_c == 2) ? wb_b : ((x+dx)%2 == (y+dy)%2) ? wb_g0 : wb_g1;
                    float p_norm_scale = 65535.0f / std::max(1.0f, (float)whiteLevel - p_bl);
                    return std::max(0.0f, p_val - p_bl) * p_norm_scale * p_wb;
"""

content = re.sub(
    r'float p_val = \(float\)bayerData\[\(y \+ dy\) \* width \+ \(x \+ dx\)\];\s*float p_bl = \(p_c == 0\) \? bl_r : \(p_c == 2\) \? bl_b : \(\(x\+dx\)%2 == \(y\+dy\)%2\) \? bl_g0 : bl_g1;\s*float p_wb = \(p_c == 0\) \? wb_r : \(p_c == 2\) \? wb_b : \(\(x\+dx\)%2 == \(y\+dy\)%2\) \? wb_g0 : wb_g1;\s*return std::max\(0\.0f, p_val - p_bl\) \* p_wb;',
    replacement2,
    content
)

content = re.sub(
    r'if \(x < 2 \|\| x >= width - 2 \|\| y < 2 \|\| y >= height - 2\) \{\s*finalImage\[out_idx \+ 0\] = \(c == 0\) \? std::min\(65535\.0f, val\) : 0;\s*finalImage\[out_idx \+ 1\] = \(c == 1\) \? std::min\(65535\.0f, val\) : 0;\s*finalImage\[out_idx \+ 2\] = \(c == 2\) \? std::min\(65535\.0f, val\) : 0;\s*continue;\s*\}',
    'if (x < 2 || x >= width - 2 || y < 2 || y >= height - 2) {\n                    continue;\n                }',
    content
)

# And after the parallel for loop, copy edges
edge_copy = """
        // Edge handling: copy nearest valid interpolated pixel
        for (int y = 0; y < height; ++y) {
            for (int x = 0; x < width; ++x) {
                if (x < 2 || x >= width - 2 || y < 2 || y >= height - 2) {
                    int nx = std::max(2, std::min(width - 3, x));
                    int ny = std::max(2, std::min(height - 3, y));
                    finalImage[(y * width + x) * 3 + 0] = finalImage[(ny * width + nx) * 3 + 0];
                    finalImage[(y * width + x) * 3 + 1] = finalImage[(ny * width + nx) * 3 + 1];
                    finalImage[(y * width + x) * 3 + 2] = finalImage[(ny * width + nx) * 3 + 2];
                }
            }
        }

        // Apply CCM, LUT, Gain, and save using existing process_and_save_image logic
"""

content = content.replace(
    '// Apply CCM, LUT, Gain, and save using existing process_and_save_image logic',
    edge_copy
)

with open('app/src/main/cpp/HdrPlusJNI.cpp', 'w') as f:
    f.write(content)
