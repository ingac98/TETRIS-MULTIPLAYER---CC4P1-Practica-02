package tetris;

import java.util.Random;

/** Tetromino del juego: Cuadro (1x1 / 2x2), T, I (2 o 3), L y Y, con 4 rotaciones. */
public class Pieza {
    private static final int[][][] BASES = {
        {{0, 0}},                                         // Cuadro 1x1
        {{0, 0}, {0, 1}, {1, 0}, {1, 1}},                 // Cuadro 2x2
        {{0, 1}, {1, 0}, {1, 1}, {1, 2}},                 // T
        {{0, 0}, {1, 0}},                                 // I de 2 bloques
        {{0, 0}, {1, 0}, {2, 0}},                         // I de 3 bloques
        {{0, 0}, {0, 1}, {1, 0}},                         // L
        {{0, 1}, {1, 0}, {1, 1}, {1, 2}, {2, 0}, {2, 2}}  // Y
    };
    private static final String[] NOMBRES = {"CUADRO1", "CUADRO2", "T", "I2", "I3", "L", "Y"};

    public String nombre;
    public int[][] celdas;   // {fila, columna} relativas a (fila, col)
    public int fila, col;    // esquina superior izquierda en el tablero

    private Pieza() { }

    /** Pieza aleatoria en su orientacion base (asi se muestra en la cola de siguientes). */
    public static Pieza aleatoria(Random rnd) { return deIndice(rnd.nextInt(BASES.length)); }

    /** Reconstruye una pieza a partir de su nombre (usado por el cliente para dibujar la cola). */
    public static Pieza porNombre(String nombre) {
        for (int i = 0; i < NOMBRES.length; i++) if (NOMBRES[i].equals(nombre)) return deIndice(i);
        return null;
    }

    private static Pieza deIndice(int k) {
        Pieza p = new Pieza();
        p.nombre = NOMBRES[k];
        p.celdas = new int[BASES[k].length][];
        for (int i = 0; i < p.celdas.length; i++) p.celdas[i] = BASES[k][i].clone();
        return p;
    }

    public Pieza copia() {
        Pieza p = new Pieza();
        p.nombre = nombre; p.fila = fila; p.col = col;
        p.celdas = new int[celdas.length][];
        for (int i = 0; i < celdas.length; i++) p.celdas[i] = celdas[i].clone();
        return p;
    }

    /** horario=true -> G2 (sentido horario); false -> G1 (antihorario). */
    public void rotar(boolean horario) {
        int minR = Integer.MAX_VALUE, minC = Integer.MAX_VALUE;
        int[][] n = new int[celdas.length][2];
        for (int i = 0; i < celdas.length; i++) {
            int r = celdas[i][0], c = celdas[i][1];
            n[i][0] = horario ? c : -c;
            n[i][1] = horario ? -r : r;
            minR = Math.min(minR, n[i][0]);
            minC = Math.min(minC, n[i][1]);
        }
        for (int[] x : n) { x[0] -= minR; x[1] -= minC; }
        celdas = n;
    }

    public int alto() { int m = 0; for (int[] c : celdas) m = Math.max(m, c[0]); return m + 1; }
    public int ancho() { int m = 0; for (int[] c : celdas) m = Math.max(m, c[1]); return m + 1; }
}
