package tetris;

import java.io.*;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * Instrumentacion ligera del servidor para las pruebas de rendimiento.
 * Registra tiempo de tick, memoria usada por la JVM y numero de hilos vivos.
 * Solo utiliza clases del SDK de Java.
 */
public class Metricas {
    private static final double NS_A_MS = 1_000_000.0;
    private static final double BYTES_A_MB = 1024.0 * 1024.0;

    public static final class Resultado {
        public final String resumen;
        public final File archivoDetalle;
        public final File archivoResumen;

        Resultado(String resumen, File archivoDetalle, File archivoResumen) {
            this.resumen = resumen;
            this.archivoDetalle = archivoDetalle;
            this.archivoResumen = archivoResumen;
        }
    }

    private static final class TickMuestra {
        final double segundo;
        final double ms;
        TickMuestra(double segundo, double ms) { this.segundo = segundo; this.ms = ms; }
    }

    private static final class RecursoMuestra {
        final double segundo;
        final int jugadores;
        final double memoriaMb;
        final int hilos;
        RecursoMuestra(double segundo, int jugadores, double memoriaMb, int hilos) {
            this.segundo = segundo;
            this.jugadores = jugadores;
            this.memoriaMb = memoriaMb;
            this.hilos = hilos;
        }
    }

    private final List<TickMuestra> ticks = new ArrayList<TickMuestra>();
    private final List<RecursoMuestra> recursos = new ArrayList<RecursoMuestra>();
    private final ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();

    private boolean activa;
    private int jugadoresInicio;
    private int filas;
    private int columnas;
    private int calentamientoSeg;
    private int medicionSeg;
    private long inicioSesionNs;
    private long inicioMedicionNs;
    private long finMedicionNs;
    private String marcaTiempo;

    public synchronized boolean iniciar(int jugadores, int filas, int columnas,
            int calentamientoSeg, int medicionSeg) {
        if (activa) return false;
        ticks.clear();
        recursos.clear();
        this.jugadoresInicio = jugadores;
        this.filas = filas;
        this.columnas = columnas;
        this.calentamientoSeg = Math.max(0, calentamientoSeg);
        this.medicionSeg = Math.max(1, medicionSeg);
        this.inicioSesionNs = System.nanoTime();
        this.inicioMedicionNs = inicioSesionNs + TimeUnitCompat.secondsToNanos(this.calentamientoSeg);
        this.finMedicionNs = inicioMedicionNs + TimeUnitCompat.secondsToNanos(this.medicionSeg);
        this.marcaTiempo = new SimpleDateFormat("yyyyMMdd_HHmmss_SSS").format(new Date());
        this.activa = true;
        return true;
    }

    public synchronized boolean activa() { return activa; }

    /** Devuelve true solo durante la ventana efectiva de medicion (despues del calentamiento). */
    public synchronized boolean midiendoAhora() {
        if (!activa) return false;
        long ahora = System.nanoTime();
        return ahora >= inicioMedicionNs && ahora <= finMedicionNs;
    }

    /**
     * Registra un tick ya medido por el servidor. El timer se coloca en ServidorTetris
     * para poder incluir la espera por el monitor del tablero sin modificar Tablero.tick().
     */
    public synchronized void registrarTick(long duracionNanos) {
        if (!activa) return;
        long ahora = System.nanoTime();
        if (ahora < inicioMedicionNs || ahora > finMedicionNs) return;
        ticks.add(new TickMuestra(segundosDesdeInicioMedicion(ahora), duracionNanos / NS_A_MS));
    }

    /**
     * Toma una muestra de recursos. Se llama aproximadamente cada segundo desde el mismo
     * ScheduledExecutorService de gravedad para no crear un hilo extra solo para medir.
     */
    public synchronized void registrarRecursos(int jugadoresActuales) {
        if (!activa) return;
        long ahora = System.nanoTime();
        if (ahora < inicioMedicionNs || ahora > finMedicionNs) return;

        Runtime rt = Runtime.getRuntime();
        long usada = rt.totalMemory() - rt.freeMemory();
        int hilos = threadBean.getThreadCount();
        recursos.add(new RecursoMuestra(segundosDesdeInicioMedicion(ahora), jugadoresActuales,
                usada / BYTES_A_MB, hilos));
    }

    public synchronized boolean tiempoCumplido() {
        return activa && System.nanoTime() >= finMedicionNs;
    }

    public synchronized int segundosParaEmpezar() {
        if (!activa) return 0;
        long restante = inicioMedicionNs - System.nanoTime();
        if (restante <= 0) return 0;
        return (int) Math.ceil(restante / 1_000_000_000.0);
    }

    /** Finaliza, guarda CSV y deja la instancia lista para otro experimento. */
    public synchronized Resultado finalizar(String motivo, int jugadoresFinales) {
        if (!activa) return null;
        activa = false;
        long ahora = System.nanoTime();
        double medicionReal = Math.max(0.0,
                (Math.min(ahora, finMedicionNs) - inicioMedicionNs) / 1_000_000_000.0);

        File dir = new File("resultados");
        if (!dir.exists()) dir.mkdirs();
        File detalle = new File(dir, "detalle_metricas_" + marcaTiempo + ".csv");
        File resumen = new File(dir, "resumen_metricas.csv");

        double tickProm = promedioTicks();
        double tickMin = minimoTicks();
        double tickMax = maximoTicks();
        double memProm = promedioMemoria();
        double memMax = maximoMemoria();
        double hilosProm = promedioHilos();
        int hilosMax = maximoHilos();

        try {
            escribirDetalle(detalle);
            escribirResumen(resumen, jugadoresFinales, medicionReal, tickProm, tickMin, tickMax,
                    memProm, memMax, hilosProm, hilosMax, motivo);
        } catch (IOException e) {
            return new Resultado("Metricas finalizadas, pero no se pudo guardar CSV: " + e.getMessage(),
                    detalle, resumen);
        }

        String texto = String.format(Locale.US,
                "Metricas finalizadas | jugadores %d->%d | ticks=%d prom=%.3f ms min=%.3f ms max=%.3f ms"
                + " | memoria prom=%.2f MB max=%.2f MB | hilos prom=%.1f max=%d",
                jugadoresInicio, jugadoresFinales, ticks.size(), tickProm, tickMin, tickMax,
                memProm, memMax, hilosProm, hilosMax);
        return new Resultado(texto, detalle, resumen);
    }

    private double segundosDesdeInicioMedicion(long ahora) {
        return Math.max(0.0, (ahora - inicioMedicionNs) / 1_000_000_000.0);
    }

    private double promedioTicks() {
        if (ticks.isEmpty()) return 0.0;
        double s = 0.0;
        for (TickMuestra t : ticks) s += t.ms;
        return s / ticks.size();
    }

    private double minimoTicks() {
        if (ticks.isEmpty()) return 0.0;
        double m = Double.MAX_VALUE;
        for (TickMuestra t : ticks) m = Math.min(m, t.ms);
        return m;
    }

    private double maximoTicks() {
        double m = 0.0;
        for (TickMuestra t : ticks) m = Math.max(m, t.ms);
        return m;
    }

    private double promedioMemoria() {
        if (recursos.isEmpty()) return 0.0;
        double s = 0.0;
        for (RecursoMuestra r : recursos) s += r.memoriaMb;
        return s / recursos.size();
    }

    private double maximoMemoria() {
        double m = 0.0;
        for (RecursoMuestra r : recursos) m = Math.max(m, r.memoriaMb);
        return m;
    }

    private double promedioHilos() {
        if (recursos.isEmpty()) return 0.0;
        double s = 0.0;
        for (RecursoMuestra r : recursos) s += r.hilos;
        return s / recursos.size();
    }

    private int maximoHilos() {
        int m = 0;
        for (RecursoMuestra r : recursos) m = Math.max(m, r.hilos);
        return m;
    }

    private void escribirDetalle(File f) throws IOException {
        PrintWriter pw = new PrintWriter(new OutputStreamWriter(new FileOutputStream(f), "UTF-8"));
        try {
            pw.println("tipo,segundo,jugadores,tick_ms,memoria_mb,hilos");
            for (TickMuestra t : ticks) {
                pw.printf(Locale.US, "TICK,%.3f,,%.6f,,%n", t.segundo, t.ms);
            }
            for (RecursoMuestra r : recursos) {
                pw.printf(Locale.US, "RECURSOS,%.3f,%d,,%.3f,%d%n",
                        r.segundo, r.jugadores, r.memoriaMb, r.hilos);
            }
        } finally {
            pw.close();
        }
    }

    private void escribirResumen(File f, int jugadoresFinales, double medicionReal,
            double tickProm, double tickMin, double tickMax,
            double memProm, double memMax, double hilosProm, int hilosMax, String motivo) throws IOException {
        boolean nueva = !f.exists() || f.length() == 0;
        PrintWriter pw = new PrintWriter(new OutputStreamWriter(new FileOutputStream(f, true), "UTF-8"));
        try {
            if (nueva) {
                pw.println("fecha,jugadores_inicio,jugadores_fin,filas,columnas,calentamiento_s,medicion_config_s,medicion_real_s,ticks,tick_prom_ms,tick_min_ms,tick_max_ms,muestras_recursos,memoria_prom_mb,memoria_max_mb,hilos_prom,hilos_max,motivo");
            }
            String motivoSeguro = motivo == null ? "" : motivo.replace(',', ';').replace('\n', ' ');
            pw.printf(Locale.US,
                    "%s,%d,%d,%d,%d,%d,%d,%.3f,%d,%.6f,%.6f,%.6f,%d,%.3f,%.3f,%.3f,%d,%s%n",
                    marcaTiempo, jugadoresInicio, jugadoresFinales, filas, columnas,
                    calentamientoSeg, medicionSeg, medicionReal, ticks.size(),
                    tickProm, tickMin, tickMax, recursos.size(), memProm, memMax,
                    hilosProm, hilosMax, motivoSeguro);
        } finally {
            pw.close();
        }
    }

    /** Sustituto minimo para mantener el codigo explicitamente compatible con Java 8. */
    private static final class TimeUnitCompat {
        static long secondsToNanos(long segundos) { return segundos * 1_000_000_000L; }
    }
}
