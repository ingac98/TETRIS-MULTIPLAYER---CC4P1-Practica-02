package tetris;

import java.io.*;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Generador de clientes simulados para pruebas de carga.
 * No dibuja Swing: conecta muchos sockets, consume los STATE y envia movimientos automaticos.
 *
 * Uso:
 *   java -cp classes tetris.ClienteCarga IP PUERTO CLIENTES [INTERVALO_MS]
 * Ejemplo:
 *   java -cp classes tetris.ClienteCarga 127.0.0.1 5684 100 1000
 */
public class ClienteCarga {
    private static final String[] COMANDOS = {"LEFT", "RIGHT", "DOWN", "ROT1", "ROT2"};

    private static final class Bot {
        final int numero;
        final Socket socket;
        final PrintWriter out;
        final Random rnd;
        volatile boolean activo = true;
        volatile String simbolo = "--";
        final AtomicInteger comandosEnviados;

        Bot(int numero, String ip, int puerto, AtomicInteger comandosEnviados) throws IOException {
            this.numero = numero;
            this.socket = new Socket(ip, puerto);
            this.socket.setTcpNoDelay(true);
            this.out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), "UTF-8"), true);
            this.rnd = new Random(1000L + numero);
            this.comandosEnviados = comandosEnviados;
            iniciarLector();
        }

        private void iniciarLector() throws IOException {
            final BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), "UTF-8"));
            Thread t = new Thread(() -> {
                try {
                    String l;
                    while ((l = in.readLine()) != null) {
                        if (l.startsWith("ID|")) {
                            String[] p = l.split("\\|");
                            if (p.length >= 3) simbolo = p[2];
                        } else if (l.startsWith("ALERT|")) {
                            // El bot consume los avisos para evitar llenar el buffer TCP.
                        }
                        // Los STATE se consumen pero no se dibujan: asi se carga la red sin Swing.
                    }
                } catch (IOException e) {
                    // Cierre normal o desconexion del servidor.
                } finally {
                    activo = false;
                    try { socket.close(); } catch (IOException e) { }
                }
            }, "carga-lector-" + numero);
            t.setDaemon(true);
            t.start();
        }

        void mover() {
            if (!activo) return;
            String cmd = COMANDOS[rnd.nextInt(COMANDOS.length)];
            synchronized (out) {
                out.println(cmd);
                if (out.checkError()) activo = false;
            }
            if (activo) comandosEnviados.incrementAndGet();
        }

        void cerrar() {
            activo = false;
            try { socket.close(); } catch (IOException e) { }
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.out.println("Uso: java -cp classes tetris.ClienteCarga IP PUERTO CLIENTES [INTERVALO_MS]");
            System.out.println("Ejemplo: java -cp classes tetris.ClienteCarga 127.0.0.1 5684 100 1000");
            return;
        }

        final String ip = args[0];
        final int puerto = Integer.parseInt(args[1]);
        final int cantidad = Integer.parseInt(args[2]);
        final int intervaloMs = args.length >= 4 ? Math.max(100, Integer.parseInt(args[3])) : 1000;
        if (cantidad <= 0 || cantidad > Tablero.MAX_JUGADORES_VISUALES) {
            throw new IllegalArgumentException("CLIENTES debe estar entre 1 y " + Tablero.MAX_JUGADORES_VISUALES);
        }

        final List<Bot> bots = new CopyOnWriteArrayList<Bot>();
        final AtomicInteger comandos = new AtomicInteger();

        System.out.println("Conectando " + cantidad + " clientes a " + ip + ":" + puerto + "...");
        for (int i = 1; i <= cantidad; i++) {
            try {
                bots.add(new Bot(i, ip, puerto, comandos));
            } catch (IOException e) {
                System.err.println("No se pudo conectar bot " + i + ": " + e.getMessage());
                break;
            }
            if (i % 25 == 0 || i == cantidad) System.out.println("Conectados: " + i + "/" + cantidad);
            Thread.sleep(5L); // Evita una rafaga extrema de conexiones en el mismo milisegundo.
        }

        if (bots.isEmpty()) {
            System.out.println("No se conecto ningun cliente.");
            return;
        }

        int workers = Math.max(2, Math.min(16, Runtime.getRuntime().availableProcessors() * 2));
        final ScheduledExecutorService movimientos = Executors.newScheduledThreadPool(workers);
        final Random offsets = new Random(12345L);
        for (final Bot b : bots) {
            long retraso = offsets.nextInt(Math.max(1, intervaloMs));
            movimientos.scheduleAtFixedRate(() -> b.mover(), retraso, intervaloMs, TimeUnit.MILLISECONDS);
        }

        final ScheduledExecutorService estado = Executors.newSingleThreadScheduledExecutor();
        estado.scheduleAtFixedRate(() -> {
            int activos = 0;
            for (Bot b : bots) if (b.activo) activos++;
            System.out.println("Bots activos: " + activos + "/" + bots.size()
                    + " | comandos enviados: " + comandos.get());
        }, 5, 5, TimeUnit.SECONDS);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            movimientos.shutdownNow();
            estado.shutdownNow();
            for (Bot b : bots) b.cerrar();
        }));

        System.out.println("Clientes listos. En el servidor pulse 'Iniciar partida'.");
        System.out.println("Cada bot enviara un movimiento aproximadamente cada " + intervaloMs + " ms.");
        System.out.println("Presione ENTER para finalizar la carga.");
        new BufferedReader(new InputStreamReader(System.in)).readLine();

        movimientos.shutdownNow();
        estado.shutdownNow();
        for (Bot b : bots) b.cerrar();
        System.out.println("Prueba de carga finalizada.");
    }
}
