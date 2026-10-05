package tetris;

import java.awt.*;
import java.io.*;
import java.net.*;
import java.util.List;
import java.util.concurrent.*;
import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

/** Servidor: acepta n clientes por sockets TCP, ejecuta el juego y replica el tablero a todos. */
public class ServidorTetris extends JFrame {
    private final JTextField txtPuerto = new JTextField("5684", 5), txtFil = new JTextField("17", 3),
            txtCol = new JTextField("27", 5), txtMs = new JTextField("500", 4);
    private final JTextField txtJugAprox = new JTextField("10", 4);
    private final JLabel lblSugerencia = new JLabel(" ");
    private final JLabel lblConectados = new JLabel("Conectados: 0");
    private final JCheckBox chkCliente = new JCheckBox("Tamano lo define el primer cliente");
    private final JButton btnServidor = new JButton("Iniciar servidor"), btnPartida = new JButton("Iniciar partida");
    private final JButton btnUsarSugerencia = new JButton("Usar recomendacion");
    private final JButton btnActualizarTablero = new JButton("Preparar tablero");

    private final JTextField txtCalentamiento = new JTextField("10", 3);
    private final JTextField txtMedicion = new JTextField("120", 4);
    private final JButton btnMetricas = new JButton("Iniciar medicion");
    private final JLabel lblMetricas = new JLabel("Metricas: inactivas");

    private final JTextArea log = new JTextArea();

    private final Tablero tablero = new Tablero(17, 27);
    private final Metricas metricas = new Metricas();
    private boolean tamanoPorCliente;
    private final List<Manejador> clientes = new CopyOnWriteArrayList<Manejador>();
    private ServerSocket ss;
    private ScheduledExecutorService bucle;

    public ServidorTetris() {
        super("Tetris Mix Multiplayer - Servidor");

        JPanel filaServidor = new JPanel(new FlowLayout(FlowLayout.LEFT));
        filaServidor.add(new JLabel("Puerto")); filaServidor.add(txtPuerto);
        filaServidor.add(new JLabel("fil")); filaServidor.add(txtFil);
        filaServidor.add(new JLabel("col")); filaServidor.add(txtCol);
        filaServidor.add(new JLabel("ms/caida")); filaServidor.add(txtMs);
        filaServidor.add(chkCliente); filaServidor.add(btnServidor);
        filaServidor.add(btnActualizarTablero); filaServidor.add(btnPartida);

        JPanel filaCapacidad = new JPanel(new FlowLayout(FlowLayout.LEFT));
        filaCapacidad.setBorder(BorderFactory.createTitledBorder("Dimensionamiento para varios jugadores"));
        filaCapacidad.add(new JLabel("Jugadores aprox."));
        filaCapacidad.add(txtJugAprox);
        filaCapacidad.add(lblSugerencia);
        filaCapacidad.add(btnUsarSugerencia);
        filaCapacidad.add(Box.createHorizontalStrut(12));
        filaCapacidad.add(lblConectados);

        JPanel filaMetricas = new JPanel(new FlowLayout(FlowLayout.LEFT));
        filaMetricas.setBorder(BorderFactory.createTitledBorder("Pruebas de rendimiento (servidor)"));
        filaMetricas.add(new JLabel("Calentamiento (s)")); filaMetricas.add(txtCalentamiento);
        filaMetricas.add(new JLabel("Medicion (s)")); filaMetricas.add(txtMedicion);
        filaMetricas.add(btnMetricas); filaMetricas.add(lblMetricas);

        JPanel superior = new JPanel();
        superior.setLayout(new BoxLayout(superior, BoxLayout.Y_AXIS));
        superior.add(filaServidor);
        superior.add(filaCapacidad);
        superior.add(filaMetricas);

        btnPartida.setEnabled(false);
        btnActualizarTablero.setEnabled(false);
        btnMetricas.setEnabled(false);
        log.setEditable(false);
        log.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        add(superior, BorderLayout.NORTH);
        add(new JScrollPane(log), BorderLayout.CENTER);

        btnServidor.addActionListener(e -> iniciarServidor());
        btnPartida.addActionListener(e -> iniciarPartida());
        btnUsarSugerencia.addActionListener(e -> aplicarSugerencia());
        btnActualizarTablero.addActionListener(e -> prepararTableroDesdeCampos(true));
        btnMetricas.addActionListener(e -> iniciarMedicion());

        txtJugAprox.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { actualizarSugerencia(); }
            public void removeUpdate(DocumentEvent e) { actualizarSugerencia(); }
            public void changedUpdate(DocumentEvent e) { actualizarSugerencia(); }
        });
        txtCol.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { actualizarSugerencia(); }
            public void removeUpdate(DocumentEvent e) { actualizarSugerencia(); }
            public void changedUpdate(DocumentEvent e) { actualizarSugerencia(); }
        });

        actualizarSugerencia();
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setSize(1180, 560);
        setLocationRelativeTo(null);
    }

    private boolean servidorActivo() {
        return ss != null && !ss.isClosed();
    }

    private void log(final String s) {
        SwingUtilities.invokeLater(() -> {
            log.append(s + "\n");
            log.setCaretPosition(log.getDocument().getLength());
        });
    }

    private Integer jugadoresAproximados() {
        try {
            int n = Integer.parseInt(txtJugAprox.getText().trim());
            return n > 0 ? n : null;
        } catch (Exception e) {
            return null;
        }
    }

    private void actualizarSugerencia() {
        Integer n = jugadoresAproximados();
        if (n == null) {
            lblSugerencia.setText("Ingrese un numero valido.");
            return;
        }
        if (n > Tablero.MAX_JUGADORES_VISUALES) {
            lblSugerencia.setText("[xx] admite hasta " + Tablero.MAX_JUGADORES_VISUALES + " jugadores simultaneos.");
            return;
        }
        int minimo = Tablero.columnasMinimasPara(n);
        int recomendado = Tablero.columnasRecomendadasPara(n);
        String estado = "";
        try {
            int actuales = Integer.parseInt(txtCol.getText().trim());
            if (actuales < minimo) estado = "  (col actual insuficiente)";
            else if (actuales < recomendado) estado = "  (col actual valida, pero ajustada)";
            else estado = "  (col actual adecuada)";
        } catch (Exception e) { }
        lblSugerencia.setText("Minimo: " + minimo + " | Recomendado: " + recomendado + estado);
    }

    private void actualizarConectados() {
        final int n = tablero.numJugadores();
        SwingUtilities.invokeLater(() -> lblConectados.setText("Conectados: " + n));
    }

    /** Habilita cambios de tablero solo fuera de una partida. */
    private void actualizarControlesPartida() {
        final boolean activo = servidorActivo();
        final boolean jugando = tablero.enJuego();
        SwingUtilities.invokeLater(() -> {
            txtFil.setEnabled(!jugando);
            txtCol.setEnabled(!jugando);
            txtMs.setEnabled(!jugando);
            btnActualizarTablero.setEnabled(activo && !jugando);
            btnUsarSugerencia.setEnabled(!jugando);
            btnPartida.setEnabled(activo && !jugando);
            btnMetricas.setEnabled(activo && jugando && !metricas.activa());
            txtCalentamiento.setEnabled(!metricas.activa());
            txtMedicion.setEnabled(!metricas.activa());
        });
    }

    private boolean prepararTableroDesdeCampos(boolean anunciar) {
        if (tablero.enJuego()) {
            JOptionPane.showMessageDialog(this, "No se puede cambiar el tablero durante una partida.");
            return false;
        }
        final int f, c;
        try {
            f = Integer.parseInt(txtFil.getText().trim());
            c = Integer.parseInt(txtCol.getText().trim());
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this, "Filas o columnas invalidas.");
            return false;
        }

        synchronized (tablero) {
            tablero.prepararNuevoTablero(f, c);
            if (servidorActivo()) {
                difundir("SIZE|" + tablero.filas() + "|" + tablero.cols());
                difundir(tablero.estado());
                if (anunciar) difundir("MSG|Tablero preparado: " + tablero.filas() + "x" + tablero.cols());
            }
        }
        txtFil.setText(String.valueOf(tablero.filas()));
        txtCol.setText(String.valueOf(tablero.cols()));
        if (anunciar) log("Tablero preparado para nueva partida: " + tablero.filas() + "x" + tablero.cols()
                + ". Los clientes permanecen conectados.");
        actualizarSugerencia();
        actualizarControlesPartida();
        return true;
    }

    private void aplicarSugerencia() {
        Integer n = jugadoresAproximados();
        if (n == null || n > Tablero.MAX_JUGADORES_VISUALES) {
            JOptionPane.showMessageDialog(this,
                    "Ingrese entre 1 y " + Tablero.MAX_JUGADORES_VISUALES + " jugadores aproximados.");
            return;
        }
        if (tablero.enJuego()) {
            JOptionPane.showMessageDialog(this, "No se puede redimensionar durante una partida.");
            return;
        }

        int recomendado = Tablero.columnasRecomendadasPara(n);
        txtCol.setText(String.valueOf(recomendado));
        if (servidorActivo()) prepararTableroDesdeCampos(false);
        log("Recomendacion aplicada: " + recomendado + " columnas para aproximadamente " + n + " jugadores.");
        actualizarSugerencia();
    }

    private void iniciarServidor() {
        try {
            int puerto = Integer.parseInt(txtPuerto.getText().trim());
            tablero.prepararNuevoTablero(Integer.parseInt(txtFil.getText().trim()), Integer.parseInt(txtCol.getText().trim()));
            ss = new ServerSocket(puerto);
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "Datos invalidos o puerto ocupado: " + ex.getMessage());
            return;
        }
        tamanoPorCliente = chkCliente.isSelected();
        chkCliente.setEnabled(false);
        txtPuerto.setEnabled(false);
        btnServidor.setEnabled(false);
        actualizarControlesPartida();
        log("Servidor escuchando en el puerto " + ss.getLocalPort() + " tablero " + tablero.filas() + "x" + tablero.cols());
        log("Filas/columnas pueden cambiarse en lobby o despues de terminar; durante la partida quedan bloqueadas.");
        log("Identificadores [xx]: primeros " + Tablero.IDENTIFICADORES_PRIORITARIOS
                + " priorizados para lectura; capacidad total " + Tablero.MAX_JUGADORES_VISUALES + ".");

        Thread t = new Thread(() -> {
            while (!ss.isClosed()) {
                try {
                    Socket s = ss.accept();
                    Manejador m = new Manejador(s);
                    m.registrar(); // El aceptador conserva el orden de conexion.
                    m.start();
                } catch (IOException e) {
                    break;
                }
            }
        }, "aceptador");
        t.setDaemon(true);
        t.start();
    }

    private void iniciarPartida() {
        if (tablero.enJuego()) return;
        int conectados = tablero.numJugadores();
        if (conectados == 0) {
            log("No hay jugadores conectados.");
            return;
        }

        int f, c;
        try {
            f = Integer.parseInt(txtFil.getText().trim());
            c = Integer.parseInt(txtCol.getText().trim());
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this, "Filas o columnas invalidas.");
            return;
        }

        int minimo = Tablero.columnasMinimasPara(conectados);
        if (c < minimo) {
            int recomendado = Tablero.columnasRecomendadasPara(conectados);
            int r = JOptionPane.showConfirmDialog(this,
                    "Hay " + conectados + " jugadores conectados y se solicitaron " + c + " columnas.\n"
                    + "Para evitar fallos de aparicion se requieren al menos " + minimo + ".\n"
                    + "Se recomienda usar " + recomendado + " columnas.\n\n"
                    + "Desea ajustar automaticamente el tablero a " + recomendado + " columnas?",
                    "Tablero demasiado estrecho", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
            if (r != JOptionPane.YES_OPTION) return;
            c = recomendado;
            txtCol.setText(String.valueOf(c));
        }

        int ms;
        try { ms = Math.max(50, Integer.parseInt(txtMs.getText().trim())); }
        catch (Exception e) { ms = 500; txtMs.setText("500"); }

        synchronized (tablero) {
            if (tablero.enJuego()) return;
            if (bucle != null) bucle.shutdownNow();

            // Antes de cada partida se aplica el tamano visible en el panel y se limpia el estado anterior.
            tablero.prepararNuevoTablero(f, c);
            difundir("SIZE|" + tablero.filas() + "|" + tablero.cols());

            String orden = tablero.iniciar();
            difundir("MSG|INICIO DE LA PARTIDA. Orden de jugada: " + orden);
            difundir(tablero.estado());
            log("Partida iniciada. " + orden);

            final ScheduledExecutorService actual = Executors.newSingleThreadScheduledExecutor();
            bucle = actual;
            actualizarControlesPartida();
            if (tablero.terminado()) {
                anunciarFin();
                return;
            }

            final int periodoMs = ms;
            actual.scheduleAtFixedRate(() -> {
                try {
                    /*
                     * TIMER DE TICK: EMPIEZA ANTES DE PEDIR EL MONITOR DEL TABLERO PARA MEDIR
                     * TANTO LA ESPERA POR CONTENCION COMO EL TRABAJO REAL DE TABLERO.TICK().
                     */
                    final boolean medirTick = metricas.activa();
                    final long inicioTickNs = medirTick ? System.nanoTime() : 0L;

                    synchronized (tablero) {
                        if (bucle != actual || actual.isShutdown()) return;
                        tablero.tick();

                        /*
                         * TIMER DE TICK: TERMINA JUSTO DESPUES DE TICK() PARA NO MEZCLAR
                         * EL COSTO DE GENERAR/ENCOLAR STATE CON EL COSTO DE LA CAIDA GRUPAL.
                         */
                        if (medirTick) metricas.registrarTick(System.nanoTime() - inicioTickNs);

                        difundir(tablero.estado());
                        anunciarFin();
                    }
                } catch (Exception e) {
                    log("Error en bucle: " + e);
                }
            }, periodoMs, periodoMs, TimeUnit.MILLISECONDS);

            // El muestreo usa EL MISMO hilo programado que la gravedad: no agrega otro hilo al conteo medido.
            actual.scheduleAtFixedRate(() -> {
                try {
                    /*
                     * MUESTREO DE MEMORIA/HILOS: SE TOMA CADA ~1 s EN EL SERVIDOR PARA OBSERVAR
                     * COMO CRECEN LOS RECURSOS DE LA JVM AL AUMENTAR LOS JUGADORES.
                     */
                    if (metricas.activa()) {
                        metricas.registrarRecursos(tablero.numJugadores());
                        actualizarEstadoMetricas();
                        if (metricas.tiempoCumplido()) finalizarMetricas("tiempo de medicion completado");
                    }
                } catch (Exception e) {
                    log("Error midiendo recursos: " + e);
                }
            }, 1, 1, TimeUnit.SECONDS);
        }
    }

    private void iniciarMedicion() {
        if (!tablero.enJuego()) {
            JOptionPane.showMessageDialog(this, "Primero debe iniciar una partida.");
            return;
        }
        int calentamiento, duracion;
        try {
            calentamiento = Math.max(0, Integer.parseInt(txtCalentamiento.getText().trim()));
            duracion = Math.max(1, Integer.parseInt(txtMedicion.getText().trim()));
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this, "Calentamiento y medicion deben ser numeros enteros validos.");
            return;
        }
        if (!metricas.iniciar(tablero.numJugadores(), tablero.filas(), tablero.cols(), calentamiento, duracion)) {
            JOptionPane.showMessageDialog(this, "Ya existe una medicion activa.");
            return;
        }
        btnMetricas.setEnabled(false);
        txtCalentamiento.setEnabled(false);
        txtMedicion.setEnabled(false);
        lblMetricas.setText(calentamiento > 0 ? "Metricas: calentamiento" : "Metricas: midiendo");
        log("Medicion iniciada: " + calentamiento + " s de calentamiento + " + duracion + " s de medicion.");
        log("Se mediran: tiempo de tick, memoria JVM y hilos vivos del servidor.");
    }

    private void actualizarEstadoMetricas() {
        SwingUtilities.invokeLater(() -> {
            if (!metricas.activa()) {
                lblMetricas.setText("Metricas: inactivas");
                return;
            }
            int espera = metricas.segundosParaEmpezar();
            lblMetricas.setText(espera > 0 ? "Metricas: calentamiento (" + espera + " s)" : "Metricas: midiendo");
        });
    }

    private void finalizarMetricas(String motivo) {
        Metricas.Resultado r = metricas.finalizar(motivo, tablero.numJugadores());
        if (r == null) return;
        log(r.resumen);
        log("CSV detalle: " + r.archivoDetalle.getPath());
        log("CSV resumen acumulado: " + r.archivoResumen.getPath());
        SwingUtilities.invokeLater(() -> {
            lblMetricas.setText("Metricas: finalizadas");
            txtCalentamiento.setEnabled(true);
            txtMedicion.setEnabled(true);
            btnMetricas.setEnabled(tablero.enJuego());
        });
    }

    // Se llama con el monitor del tablero; anuncia el resultado una sola vez.
    private void anunciarFin() {
        if (tablero.terminado() && bucle != null && !bucle.isShutdown()) {
            difundir("ALERT|" + tablero.resultado());
            log(tablero.resultado());
            finalizarMetricas("partida terminada");
            bucle.shutdown();
            actualizarControlesPartida();
        }
    }

    // La E/S real se realiza en el hilo escritor de cada cliente.
    private void difundir(String linea) {
        for (Manejador m : clientes) m.enviar(linea);
    }

    /** Un hilo lector + un hilo escritor por cliente. */
    private class Manejador extends Thread {
        private final Socket s;
        private final ColaSalida salida = new ColaSalida();
        private Tablero.Jugador jug;

        Manejador(Socket s) {
            this.s = s;
            setDaemon(true);
        }

        void enviar(String l) { salida.ofrecer(l); }

        void registrar() {
            synchronized (tablero) {
                jug = tablero.agregarJugador();
                if (jug == null) {
                    if (tablero.enJuego()) {
                        enviar("ALERT|Partida en curso, intenta mas tarde.");
                    } else {
                        enviar("ALERT|Se alcanzo el limite visual de " + Tablero.MAX_JUGADORES_VISUALES
                                + " jugadores para bloques [xx].");
                    }
                    salida.cerrar(false);
                    return;
                }
                clientes.add(this);
                setName("lector-jugador-" + jug.id);
                enviar("ID|" + jug.id + "|" + jug.simbolo);
                enviar("SIZE|" + tablero.filas() + "|" + tablero.cols());
                difundir("MSG|Se conecto el Jugador " + jug.simbolo
                        + " (jugadores: " + tablero.numJugadores() + ")");
                difundir(tablero.estado());
            }
            actualizarConectados();
            log("Conectado " + s.getRemoteSocketAddress() + " como " + jug.simbolo + " (id interno " + jug.id + ")");
        }

        private void cerrarSocket() {
            try { s.close(); } catch (IOException e) { }
        }

        @Override public void run() {
            Thread escritor = new Thread(() -> {
                try (BufferedWriter pw = new BufferedWriter(new OutputStreamWriter(s.getOutputStream(), "UTF-8"))) {
                    String l;
                    while ((l = salida.tomar()) != null) {
                        pw.write(l);
                        pw.newLine();
                        pw.flush();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (IOException e) {
                    // Cerrar el socket despierta al lector y retira al jugador.
                } finally {
                    salida.cerrar(true);
                    cerrarSocket();
                }
            }, "escritor-" + getName());
            escritor.setDaemon(true);
            escritor.start();
            if (jug == null) return; // El escritor envia el rechazo y cierra.

            try (BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), "UTF-8"))) {
                String l;
                while ((l = in.readLine()) != null) procesar(l.trim());
            } catch (IOException e) {
                // Desconexion del cliente.
            } finally {
                salida.cerrar(true);
                cerrarSocket();
                synchronized (tablero) {
                    clientes.remove(this);
                    tablero.quitarJugador(jug.id);
                    difundir("MSG|Salio el Jugador " + jug.simbolo
                            + ". Su pieza activa se retira; sus bloques fijos permanecen.");
                    difundir(tablero.estado());
                    anunciarFin();
                }
                actualizarConectados();
                log("Desconectado Jugador " + jug.simbolo);
            }
        }

        private void procesar(String l) {
            if (l.length() == 0) return;
            String[] p = l.split("\\s+");
            synchronized (tablero) {
                if (p[0].equals("HELLO") && p.length >= 3 && tamanoPorCliente
                        && !tablero.enJuego() && tablero.numJugadores() == 1) {
                    try {
                        tablero.prepararNuevoTablero(Integer.parseInt(p[1]), Integer.parseInt(p[2]));
                        difundir("SIZE|" + tablero.filas() + "|" + tablero.cols());
                        difundir(tablero.estado());
                        final int ff = tablero.filas(), cc = tablero.cols();
                        SwingUtilities.invokeLater(() -> {
                            txtFil.setText(String.valueOf(ff));
                            txtCol.setText(String.valueOf(cc));
                        });
                        log("Tamano fijado por el primer cliente: " + tablero.filas() + "x" + tablero.cols());
                    } catch (NumberFormatException e) { }
                } else if (p[0].equals("LEFT")) tablero.mover(jug.id, -1);
                else if (p[0].equals("RIGHT")) tablero.mover(jug.id, 1);
                else if (p[0].equals("DOWN")) tablero.bajar(jug.id);
                else if (p[0].equals("UP")) tablero.caer(jug.id);
                else if (p[0].equals("ROT1")) tablero.rotar(jug.id, false);   // G1 antihorario
                else if (p[0].equals("ROT2")) tablero.rotar(jug.id, true);    // G2 horario
                else if (!p[0].equals("HELLO")) return;
                difundir(tablero.estado());
                anunciarFin();
            }
        }
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new ServidorTetris().setVisible(true));
    }
}
