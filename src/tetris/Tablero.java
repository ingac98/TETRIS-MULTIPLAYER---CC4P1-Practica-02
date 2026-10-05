package tetris;

import java.util.*;

/** Estado y reglas del tablero compartido. Todos los metodos publicos son thread-safe. */
public class Tablero {
    /**
     * Cada bloque visible usa exactamente dos caracteres: [xx].
     * Se priorizan identificadores faciles de distinguir para los primeros 286 jugadores:
     *   1..26   -> aa, bb, cc, ..., zz
     *   27..286 -> a0, b0, ..., z0, a1, ..., z9
     * Despues se usan las combinaciones letra-letra restantes para pruebas de carga.
     */
    private static final String LETRAS = "abcdefghijklmnopqrstuvwxyz";
    private static final String DIGITOS = "0123456789";
    public static final int IDENTIFICADORES_PRIORITARIOS = 26 + 26 * 10; // 286
    public static final int MAX_JUGADORES_VISUALES = 26 * 36; // 936 combinaciones unicas
    public static final int MAX_COLUMNAS = 10000; // limite tecnico preventivo, no de la practica

    public static class Jugador {
        public final int id;
        public final String simbolo;
        public int puntos;
        public Pieza pieza;

        Jugador(int id, String simbolo) {
            this.id = id;
            this.simbolo = simbolo;
        }
    }

    private int filas, cols;
    /** null = vacio; de lo contrario contiene el simbolo fijo de dos caracteres del jugador. */
    private String[][] fijo;
    private final LinkedHashMap<Integer, Jugador> jugadores = new LinkedHashMap<Integer, Jugador>();
    private boolean jugando, terminado;
    private String resultado = "";
    private int contador = 0;
    private final Random rnd = new Random();

    /** Cola global de piezas siguientes, decidida por el servidor y visible para todos. */
    public static final int TAM_COLA = 5;
    private final LinkedList<Pieza> cola = new LinkedList<Pieza>();

    public Tablero(int filas, int cols) {
        redimensionar(filas, cols);
        regenerarCola();
    }

    /** Minimo seguro: reserva hasta 3 columnas por jugador (ancho maximo de aparicion). */
    public static int columnasMinimasPara(int numeroJugadores) {
        if (numeroJugadores <= 0) return 8;
        long valor = 3L * numeroJugadores;
        return (int) Math.min(MAX_COLUMNAS, Math.max(8L, valor));
    }

    /** Recomendacion practica: 4 columnas por jugador para dejar algo de espacio lateral. */
    public static int columnasRecomendadasPara(int numeroJugadores) {
        if (numeroJugadores <= 0) return 8;
        long valor = 4L * numeroJugadores;
        return (int) Math.min(MAX_COLUMNAS, Math.max(8L, valor));
    }

    /**
     * Convierte un indice 0..935 en un simbolo fijo de dos caracteres.
     * El orden esta pensado para legibilidad humana antes que para orden alfabetico.
     */
    public static String simboloParaIndice(int indice) {
        if (indice < 0 || indice >= MAX_JUGADORES_VISUALES) return null;

        // 1) Primeros 26: aa, bb, cc, ..., zz.
        if (indice < 26) {
            char letra = LETRAS.charAt(indice);
            return new String(new char[]{letra, letra});
        }

        // 2) Siguientes 260: a0, b0, ..., z0, a1, ..., z9.
        int restante = indice - 26;
        if (restante < 26 * DIGITOS.length()) {
            int numero = restante / 26;
            int letra = restante % 26;
            return new String(new char[]{LETRAS.charAt(letra), DIGITOS.charAt(numero)});
        }

        // 3) Restantes 650: combinaciones letra-letra distintas (ab, ac, ..., zy).
        restante -= 26 * DIGITOS.length();
        int primero = restante / 25;
        int posSegundo = restante % 25;
        // Se omite la letra igual a la primera porque aa, bb, ... ya fueron usadas.
        int segundo = posSegundo >= primero ? posSegundo + 1 : posSegundo;
        return new String(new char[]{LETRAS.charAt(primero), LETRAS.charAt(segundo)});
    }

    private String siguienteSimboloLibre() {
        HashSet<String> usados = new HashSet<String>();
        for (Jugador j : jugadores.values()) usados.add(j.simbolo);
        for (int i = 0; i < MAX_JUGADORES_VISUALES; i++) {
            String s = simboloParaIndice(i);
            if (!usados.contains(s)) return s;
        }
        return null;
    }

    private void regenerarCola() {
        cola.clear();
        while (cola.size() < TAM_COLA) cola.addLast(Pieza.aleatoria(rnd));
    }

    /**
     * Entrega la siguiente pieza de la cola y repone una nueva al final.
     * Es privado y solo se llama con el monitor del tablero tomado (metodos synchronized), asi que
     * si dos jugadores fijan su pieza a la vez, las llamadas se serializan y cada uno recibe una
     * pieza distinta, en el orden en que la cola las tenia.
     */
    private Pieza tomarSiguiente() {
        Pieza p = cola.removeFirst();
        cola.addLast(Pieza.aleatoria(rnd));
        return p;
    }

    public synchronized void redimensionar(int f, int c) {
        filas = Math.max(6, Math.min(f, 60));
        cols = Math.max(8, Math.min(c, MAX_COLUMNAS));
        fijo = new String[filas][cols];
    }

    /**
     * Prepara un tablero limpio para una nueva partida sin desconectar a los jugadores.
     * Solo debe usarse cuando no hay una partida en curso.
     */
    public synchronized void prepararNuevoTablero(int f, int c) {
        if (jugando) throw new IllegalStateException("No se puede cambiar el tablero durante una partida.");
        redimensionar(f, c);
        terminado = false;
        resultado = "";
        for (Jugador j : jugadores.values()) {
            j.puntos = 0;
            j.pieza = null;
        }
        regenerarCola();
    }

    public synchronized int filas() { return filas; }
    public synchronized int cols() { return cols; }
    public synchronized boolean enJuego() { return jugando; }
    public synchronized boolean terminado() { return terminado; }
    public synchronized String resultado() { return resultado; }
    public synchronized int numJugadores() { return jugadores.size(); }

    public synchronized Jugador agregarJugador() {
        if (jugando) return null;
        String simbolo = siguienteSimboloLibre();
        if (simbolo == null) return null;
        Jugador j = new Jugador(++contador, simbolo);
        jugadores.put(j.id, j);
        return j;
    }

    public synchronized void quitarJugador(int id) {
        Jugador saliente = jugadores.remove(id);
        // Desaparece la pieza activa; los bloques de fijo permanecen.
        if (saliente != null) saliente.pieza = null;
        if (jugando && jugadores.isEmpty()) {
            jugando = false;
            terminado = true;
            resultado = "Todos los jugadores se fueron.";
        }
    }

    /** Inicia (o reinicia) la partida. Devuelve el orden de jugada. */
    public synchronized String iniciar() {
        fijo = new String[filas][cols];
        terminado = false;
        resultado = "";
        jugando = true;
        regenerarCola();
        StringBuilder orden = new StringBuilder();
        int idx = 0;
        for (Jugador j : jugadores.values()) {
            j.puntos = 0;
            j.pieza = null;
            orden.append(idx + 1).append(". Jugador ").append(j.simbolo).append("   ");
            idx++;
        }
        idx = 0;
        for (Jugador j : jugadores.values()) {
            if (!aparecer(j, idx++)) {
                terminar();
                break;
            }
        }
        return orden.toString().trim();
    }

    private int indiceDe(Jugador j) {
        int i = 0;
        for (Jugador x : jugadores.values()) {
            if (x == j) return i;
            i++;
        }
        return 0;
    }

    private boolean aparecer(Jugador j, int idx) {
        Pieza p = tomarSiguiente();
        p.fila = 0;
        int max = cols - p.ancho();
        if (max < 0) return false;
        int n = Math.max(1, jugadores.size());
        int pref = (int) ((idx + 0.5) * cols / n) - p.ancho() / 2;
        pref = Math.max(0, Math.min(pref, max));
        for (int k = 0; k <= max; k++) {
            p.col = (pref + k) % (max + 1);
            if (!colisiona(p, j.id, p.fila, p.col)) {
                j.pieza = p;
                return true;
            }
        }
        return false;
    }

    private boolean colisiona(Pieza p, int id, int f, int c) {
        for (int[] ce : p.celdas) {
            int r = f + ce[0], cc = c + ce[1];
            if (r < 0 || r >= filas || cc < 0 || cc >= cols) return true;
            if (fijo[r][cc] != null) return true;
            for (Jugador o : jugadores.values()) { // no se pueden sobreponer
                if (o.id == id || o.pieza == null) continue;
                for (int[] oc : o.pieza.celdas) {
                    if (o.pieza.fila + oc[0] == r && o.pieza.col + oc[1] == cc) return true;
                }
            }
        }
        return false;
    }

    /** dc: -1 izq, +1 der. */
    public synchronized void mover(int id, int dc) {
        Jugador j = jugadores.get(id);
        if (!jugando || j == null || j.pieza == null) return;
        if (!colisiona(j.pieza, id, j.pieza.fila, j.pieza.col + dc)) j.pieza.col += dc;
    }

    public synchronized void bajar(int id) {
        Jugador j = jugadores.get(id);
        if (!jugando || j == null || j.pieza == null) return;
        Set<Jugador> grupo = grupoApoyado(j);
        if (puedeBajar(grupo)) desplazarGrupo(grupo);
    }

    /** Caida rapida: incorpora las piezas que encuentra y baja el grupo. */
    public synchronized void caer(int id) {
        Jugador j = jugadores.get(id);
        if (!jugando || j == null || j.pieza == null) return;
        Set<Jugador> grupo = grupoApoyado(j);
        while (puedeBajar(grupo)) {
            desplazarGrupo(grupo);
            grupo = grupoApoyado(j);
        }
        fijarGrupo(grupo);
    }

    public synchronized void rotar(int id, boolean horario) {
        Jugador j = jugadores.get(id);
        if (!jugando || j == null || j.pieza == null) return;
        Pieza t = j.pieza.copia();
        t.rotar(horario);
        int[] kicks = {0, -1, 1, -2, 2};
        for (int k : kicks) {
            if (!colisiona(t, id, t.fila, t.col + k)) {
                t.col += k;
                j.pieza = t;
                return;
            }
        }
    }

    // Contacto vertical: una pieza esta apoyada sobre la otra.
    private boolean enContacto(Pieza a, Pieza b) {
        for (int[] ca : a.celdas) for (int[] cb : b.celdas) {
            if (a.col + ca[1] == b.col + cb[1]
                    && Math.abs(a.fila + ca[0] - b.fila - cb[0]) == 1) return true;
        }
        return false;
    }

    private Set<Jugador> grupoApoyado(Jugador inicial) {
        Set<Jugador> grupo = new LinkedHashSet<Jugador>();
        ArrayDeque<Jugador> pendientes = new ArrayDeque<Jugador>();
        grupo.add(inicial);
        pendientes.add(inicial);
        while (!pendientes.isEmpty()) {
            Jugador actual = pendientes.removeFirst();
            for (Jugador otro : jugadores.values()) {
                if (otro.pieza != null && !grupo.contains(otro)
                        && enContacto(actual.pieza, otro.pieza)) {
                    grupo.add(otro);
                    pendientes.addLast(otro);
                }
            }
        }
        return grupo;
    }

    private boolean puedeBajar(Set<Jugador> grupo) {
        for (Jugador j : grupo) for (int[] celda : j.pieza.celdas) {
            int r = j.pieza.fila + celda[0] + 1;
            int c = j.pieza.col + celda[1];
            if (r >= filas || fijo[r][c] != null) return false;
        }
        // Cualquier pieza activa que obstruya el descenso ya pertenece al grupo.
        return true;
    }

    private void desplazarGrupo(Set<Jugador> grupo) {
        for (Jugador j : grupo) j.pieza.fila++;
    }

    /** Cada pieza baja una vez por tick, junto con sus piezas apoyadas. */
    public synchronized boolean tick() {
        if (!jugando) return false;
        Set<Jugador> visitados = new HashSet<Jugador>();
        List<Set<Jugador>> moviles = new ArrayList<Set<Jugador>>();
        Set<Jugador> apoyados = new LinkedHashSet<Jugador>();
        for (Jugador j : jugadores.values()) {
            if (j.pieza == null || visitados.contains(j)) continue;
            Set<Jugador> grupo = grupoApoyado(j);
            visitados.addAll(grupo);
            if (puedeBajar(grupo)) moviles.add(grupo);
            else apoyados.addAll(grupo);
        }
        // Decidir primero y mover despues evita depender del orden de jugadores.
        for (Set<Jugador> grupo : moviles) desplazarGrupo(grupo);
        if (!apoyados.isEmpty()) fijarGrupo(apoyados);
        return jugando;
    }

    private void fijarGrupo(Set<Jugador> grupo) {
        boolean[] completas = new boolean[filas];
        // Se colocan todas las piezas antes de eliminar filas o generar nuevas.
        for (Jugador j : jugadores.values()) {
            if (!grupo.contains(j)) continue;
            for (int[] ce : j.pieza.celdas) {
                fijo[j.pieza.fila + ce[0]][j.pieza.col + ce[1]] = j.simbolo;
            }
            j.pieza = null;
            int n = 0;
            for (int r = 0; r < filas; r++) {
                if (completas[r]) continue;
                boolean llena = true;
                for (int c = 0; c < cols; c++) {
                    if (fijo[r][c] == null) {
                        llena = false;
                        break;
                    }
                }
                if (llena) {
                    completas[r] = true;
                    n++;
                }
            }
            j.puntos += 100 * n * n;
        }
        limpiarLineas();
        for (Jugador o : jugadores.values()) {
            if (o.pieza == null) continue;
            while (colisiona(o.pieza, o.id, o.pieza.fila, o.pieza.col) && o.pieza.fila > 0) o.pieza.fila--;
            if (colisiona(o.pieza, o.id, o.pieza.fila, o.pieza.col)) {
                terminar();
                return;
            }
        }
        for (int c = 0; c < cols; c++) {
            if (fijo[0][c] != null) {
                terminar();
                return;
            }
        }
        for (Jugador j : jugadores.values()) {
            if (grupo.contains(j) && !aparecer(j, indiceDe(j))) {
                terminar();
                return;
            }
        }
    }

    private int limpiarLineas() {
        int w = filas - 1, n = 0;
        for (int r = filas - 1; r >= 0; r--) {
            boolean llena = true;
            for (int c = 0; c < cols; c++) {
                if (fijo[r][c] == null) {
                    llena = false;
                    break;
                }
            }
            if (llena) n++;
            else {
                if (w != r) fijo[w] = fijo[r].clone();
                w--;
            }
        }
        for (int r = w; r >= 0; r--) fijo[r] = new String[cols];
        return n;
    }

    private void terminar() {
        jugando = false;
        terminado = true;
        int max = 0;
        for (Jugador j : jugadores.values()) max = Math.max(max, j.puntos);
        if (max == 0) {
            resultado = "El tablero se lleno sin que hubiera un ganador.";
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (Jugador j : jugadores.values()) {
            if (j.puntos == max) {
                if (sb.length() > 0) sb.append(" y ");
                sb.append("Jugador ").append(j.simbolo);
            }
        }
        resultado = "Fin del juego. Ganador: " + sb + " con " + max + " puntos.";
    }

    /**
     * Protocolo STATE:
     * STATE|filas separadas por ';'|puntajes|estado|cola
     * Cada celda ocupa exactamente 2 caracteres: ".." si esta vacia, simbolo en minuscula
     * si esta fija y simbolo en mayuscula si corresponde a una pieza activa.
     */
    public synchronized String estado() {
        String[][] g = new String[filas][];
        for (int r = 0; r < filas; r++) g[r] = fijo[r].clone();
        for (Jugador j : jugadores.values()) {
            if (j.pieza == null) continue;
            String activo = j.simbolo.toUpperCase(Locale.ROOT);
            for (int[] ce : j.pieza.celdas) {
                g[j.pieza.fila + ce[0]][j.pieza.col + ce[1]] = activo;
            }
        }

        StringBuilder sb = new StringBuilder("STATE|");
        for (int r = 0; r < filas; r++) {
            for (int c = 0; c < cols; c++) sb.append(g[r][c] == null ? ".." : g[r][c]);
            if (r < filas - 1) sb.append(';');
        }
        sb.append('|');

        boolean primero = true;
        for (Jugador j : jugadores.values()) {
            if (!primero) sb.append(',');
            sb.append(j.simbolo).append('=').append(j.puntos);
            primero = false;
        }

        sb.append('|').append(terminado ? "OVER" : jugando ? "PLAY" : "LOBBY");
        sb.append('|');
        boolean pr = true;
        for (Pieza p : cola) {
            if (!pr) sb.append(',');
            sb.append(p.nombre);
            pr = false;
        }
        return sb.toString();
    }
}
