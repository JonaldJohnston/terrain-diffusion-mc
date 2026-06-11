package com.github.xandergos.terraindiffusionmc.infinitetensor;

import java.util.Arrays;

/**
 * An N-dimensional float array with row-major (C-order) layout.
 * Used as the data container for InfiniteTensor computations.
 */
public class FloatTensor {
    public final int[] shape;
    public final float[] data;
    final int[] strides;

    public FloatTensor(int[] shape) {
        this.shape = shape.clone();
        int total = 1;
        for (int d : shape) total *= d;
        this.data = new float[total];
        this.strides = computeStrides(shape);
    }

    public FloatTensor(int[] shape, float[] data) {
        this.shape = shape.clone();
        this.data = data;
        this.strides = computeStrides(shape);
    }

    static int[] computeStrides(int[] shape) {
        int n = shape.length;
        int[] s = new int[n];
        int stride = 1;
        for (int i = n - 1; i >= 0; i--) {
            s[i] = stride;
            stride *= shape[i];
        }
        return s;
    }

    public int ndim() {
        return shape.length;
    }

    public long byteSize() {
        return (long) data.length * Float.BYTES;
    }

    /**
     * Add values from src into this tensor at a sub-region.
     * dstRegion[d] = {start, stop}, srcRegion[d] = {start, stop}.
     * The region sizes must match in every dimension.
     */
    public void addFrom(FloatTensor src, int[][] dstRegion, int[][] srcRegion) {
        int n = shape.length;
        int[] count = new int[n];
        for (int d = 0; d < n; d++) {
            count[d] = dstRegion[d][1] - dstRegion[d][0];
            if (count[d] == 0) return;
        }

        // Base offsets of the region start in each tensor
        int dstBase = 0, srcBase = 0;
        for (int d = 0; d < n; d++) {
            dstBase += dstRegion[d][0] * strides[d];
            srcBase += srcRegion[d][0] * src.strides[d];
        }

        // The last dimension is contiguous in both tensors (C-order, stride 1),
        // so run a tight inner loop over it and walk the outer dimensions with
        // an odometer - no div/mod per element.
        int last = count[n - 1];
        int[] idx = new int[n];
        while (true) {
            for (int i = 0; i < last; i++) {
                data[dstBase + i] += src.data[srcBase + i];
            }
            int d = n - 2;
            while (d >= 0) {
                idx[d]++;
                dstBase += strides[d];
                srcBase += src.strides[d];
                if (idx[d] < count[d]) break;
                // Rewind this dimension and carry into the next outer one
                idx[d] = 0;
                dstBase -= count[d] * strides[d];
                srcBase -= count[d] * src.strides[d];
                d--;
            }
            if (d < 0) break;
        }
    }

    /**
     * Extract a contiguous sub-region as a new zero-based tensor.
     * region[d] = {start, stop}.
     */
    public FloatTensor slice(int[][] region) {
        int n = shape.length;
        int[] newShape = new int[n];
        for (int d = 0; d < n; d++) {
            newShape[d] = region[d][1] - region[d][0];
        }
        FloatTensor result = new FloatTensor(newShape);
        int[][] dstRegion = new int[n][2];
        for (int d = 0; d < n; d++) {
            dstRegion[d][0] = 0;
            dstRegion[d][1] = newShape[d];
        }
        result.addFrom(this, dstRegion, region);
        return result;
    }

    @Override
    public String toString() {
        return "FloatTensor(shape=" + Arrays.toString(shape) + ")";
    }
}
