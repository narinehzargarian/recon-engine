package io.github.narinehzargarian.recon.internal;

import java.util.Arrays;

/**
 * Minimum-cost assignment (the Hungarian algorithm), O(n^2 m) for an n by m matrix with n at most m.
 *
 * <p>Pairs that aren't allowed carry {@link #FORBIDDEN} cost. Because that cost dominates every real
 * one, the result first maximizes how many real pairs are matched, and then minimizes their cost.
 */
public final class Assignment {

    public static final double FORBIDDEN = 1e9;

    private Assignment() {
    }

    /** Returns, for each row, its assigned column, or -1 if it got only a forbidden pair. */
    public static int[] solve(double[][] cost) {
        int rows = cost.length;
        if (rows == 0) {
            return new int[0];
        }
        int columns = cost[0].length;
        if (rows > columns) {
            int[] byColumn = solve(transpose(cost));
            int[] result = new int[rows];
            Arrays.fill(result, -1);
            for (int column = 0; column < byColumn.length; column++) {
                if (byColumn[column] >= 0) {
                    result[byColumn[column]] = column;
                }
            }
            return result;
        }

        // Potentials u and v, and p[j] = the row assigned to column j, all 1-indexed.
        double[] u = new double[rows + 1];
        double[] v = new double[columns + 1];
        int[] p = new int[columns + 1];
        int[] way = new int[columns + 1];
        for (int row = 1; row <= rows; row++) {
            p[0] = row;
            int column0 = 0;
            double[] minimum = new double[columns + 1];
            boolean[] used = new boolean[columns + 1];
            Arrays.fill(minimum, Double.POSITIVE_INFINITY);
            do {
                used[column0] = true;
                int row0 = p[column0];
                double delta = Double.POSITIVE_INFINITY;
                int column1 = 0;
                for (int column = 1; column <= columns; column++) {
                    if (!used[column]) {
                        double reduced = cost[row0 - 1][column - 1] - u[row0] - v[column];
                        if (reduced < minimum[column]) {
                            minimum[column] = reduced;
                            way[column] = column0;
                        }
                        if (minimum[column] < delta) {
                            delta = minimum[column];
                            column1 = column;
                        }
                    }
                }
                for (int column = 0; column <= columns; column++) {
                    if (used[column]) {
                        u[p[column]] += delta;
                        v[column] -= delta;
                    } else {
                        minimum[column] -= delta;
                    }
                }
                column0 = column1;
            } while (p[column0] != 0);
            do {
                int column1 = way[column0];
                p[column0] = p[column1];
                column0 = column1;
            } while (column0 != 0);
        }

        int[] result = new int[rows];
        Arrays.fill(result, -1);
        for (int column = 1; column <= columns; column++) {
            if (p[column] != 0 && cost[p[column] - 1][column - 1] < FORBIDDEN) {
                result[p[column] - 1] = column - 1;
            }
        }
        return result;
    }

    private static double[][] transpose(double[][] matrix) {
        double[][] result = new double[matrix[0].length][matrix.length];
        for (int i = 0; i < matrix.length; i++) {
            for (int j = 0; j < matrix[0].length; j++) {
                result[j][i] = matrix[i][j];
            }
        }
        return result;
    }
}
