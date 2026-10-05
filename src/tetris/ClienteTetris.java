package tetris;

import java.awt.*;
import java.awt.event.KeyEvent;
import java.io.*;
import java.net.Socket;
import javax.swing.*;

/** Cliente/jugador: interfaz Swing con tablero en caracteres. Solo usa java.net.Socket. */
public class ClienteTetris extends JFrame {
    /** [xx] ocupa 4 caracteres y se reserva 1 caracter adicional como separacion visual. */
    private static final int ANCHO_CELDA_VISUAL = 5;

    private final JTextField txtIp = new JTextField("25.37.128.145", 10), txtFil = new JTextField("17", 3),
            txtCol = new JTextField("27", 4), txtPuerto = new JTextField("5684", 5);
    private final JButton btnConectar = new JButton("Connect");
    private final JTextArea area = new JTextArea();
    private final JTextArea areaCola = new JTextArea();
    private final JTextArea areaEventos = new JTextArea(5, 40);
    private final JTextArea areaPuntos = new JTextArea(2, 40);
    private final JLabel lblJugador = new JLabel("Jugador --");
    private Socket socket;
    private PrintWriter out;
    private volatile boolean conectado = false;
    private int filas = 17, cols = 27;

    public ClienteTetris() {
        super("Tetris Mix Multiplayer - Cliente");
        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT));
        top.add(new JLabel("IP")); top.add(txtIp);
        top.add(new JLabel("fil")); top.add(txtFil);
        top.add(new JLabel("col")); top.add(txtCol);
        top.add(new JLabel("Port")); top.add(txtPuerto);
        top.add(btnConectar);

        area.setEditable(false); area.setFocusable(false);
        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));

        JPanel ctl = new JPanel(new GridLayout(0, 1, 4, 4));
        ctl.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        ctl.add(lblJugador);
        ctl.add(boton("^", "UP"));
        JPanel lr = new JPanel(new GridLayout(1, 2, 4, 4));
        lr.add(boton("<", "LEFT")); lr.add(boton(">", "RIGHT"));
        ctl.add(lr);
        ctl.add(boton("V", "DOWN"));
        ctl.add(new JLabel("Girar (Z / X)"));
        JPanel g = new JPanel(new GridLayout(1, 2, 4, 4));
        g.add(boton("G1", "ROT1")); g.add(boton("G2", "ROT2"));
        ctl.add(g);

        areaCola.setEditable(false); areaCola.setFocusable(false);
        areaCola.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        areaCola.setText("Siguientes piezas\n(esperando servidor)");
        JScrollPane spCola = new JScrollPane(areaCola);
        spCola.setPreferredSize(new Dimension(190, 100));
        add(spCola, BorderLayout.WEST);

        add(top, BorderLayout.NORTH);
        add(new JScrollPane(area), BorderLayout.CENTER);
        add(ctl, BorderLayout.EAST);

        areaPuntos.setEditable(false); areaPuntos.setFocusable(false);
        areaPuntos.setLineWrap(true); areaPuntos.setWrapStyleWord(true);
        areaPuntos.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        JScrollPane puntos = new JScrollPane(areaPuntos);
        puntos.setBorder(BorderFactory.createTitledBorder("Puntajes"));
        puntos.setPreferredSize(new Dimension(100, 70));

        areaEventos.setEditable(false); areaEventos.setFocusable(false);
        areaEventos.setLineWrap(true); areaEventos.setWrapStyleWord(true);
        JScrollPane eventos = new JScrollPane(areaEventos);
        eventos.setBorder(BorderFactory.createTitledBorder("Eventos"));

        JPanel inferior = new JPanel(new BorderLayout());
        inferior.add(puntos, BorderLayout.NORTH);
        inferior.add(eventos, BorderLayout.CENTER);
        add(inferior, BorderLayout.SOUTH);

        btnConectar.addActionListener(e -> conectar());
        btnConectar.setFocusable(false);

        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(ev -> {
            if (!conectado || ev.getID() != KeyEvent.KEY_PRESSED) return false;
            switch (ev.getKeyCode()) {
                case KeyEvent.VK_LEFT: enviar("LEFT"); return true;
                case KeyEvent.VK_RIGHT: enviar("RIGHT"); return true;
                case KeyEvent.VK_DOWN: enviar("DOWN"); return true;
                case KeyEvent.VK_UP: enviar("UP"); return true;
                case KeyEvent.VK_Z: enviar("ROT1"); return true;
                case KeyEvent.VK_X: enviar("ROT2"); return true;
                default: return false;
            }
        });
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setSize(1080, 720);
        setLocationRelativeTo(null);
    }

    private JButton boton(String txt, final String cmd) {
        JButton b = new JButton(txt);
        b.setFocusable(false);
        b.addActionListener(e -> enviar(cmd));
        return b;
    }

    private void enviar(String s) { if (out != null) out.println(s); }

    private void conectar() {
        try {
            socket = new Socket(txtIp.getText().trim(), Integer.parseInt(txtPuerto.getText().trim()));
            out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), "UTF-8"), true);
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "No se pudo conectar: " + ex.getMessage());
            return;
        }
        conectado = true;
        btnConectar.setEnabled(false);
        for (JTextField f : new JTextField[]{txtIp, txtPuerto}) f.setEnabled(false);
        enviar("HELLO " + txtFil.getText().trim() + " " + txtCol.getText().trim());
        Thread t = new Thread(() -> {
            try (BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), "UTF-8"))) {
                String l;
                while ((l = in.readLine()) != null) procesar(l);
            } catch (IOException e) { }
            conectado = false;
            try { socket.close(); } catch (IOException e) { }
            SwingUtilities.invokeLater(() -> {
                out = null;
                areaPuntos.setText("Desconectado del servidor.");
                registrarEvento("Desconectado del servidor.");
                btnConectar.setEnabled(true);
                txtIp.setEnabled(true); txtPuerto.setEnabled(true);
            });
        }, "lector");
        t.setDaemon(true); t.start();
    }

    private void procesar(final String l) {
        final String[] p = l.split("\\|");
        SwingUtilities.invokeLater(() -> {
            if (p[0].equals("ID") && p.length >= 3) {
                lblJugador.setText("Jugador " + p[2]);
            } else if (p[0].equals("SIZE") && p.length >= 3) {
                filas = Integer.parseInt(p[1]); cols = Integer.parseInt(p[2]);
                txtFil.setText(p[1]); txtCol.setText(p[2]);
            } else if (p[0].equals("MSG") || p[0].equals("ALERT")) {
                String mensaje = p.length > 1 ? p[1] : "";
                registrarEvento(mensaje);
                if (p[0].equals("ALERT")) {
                    JOptionPane.showMessageDialog(this, mensaje, "Aviso", JOptionPane.INFORMATION_MESSAGE);
                }
            } else if (p[0].equals("STATE") && p.length >= 4) {
                dibujar(p[1], p[2], p[3]);
                dibujarCola(p.length >= 5 ? p[4] : "");
            }
        });
    }

    private void registrarEvento(String mensaje) {
        areaEventos.append(mensaje + "\n");
        if (areaEventos.getLineCount() > 200) {
            try { areaEventos.replaceRange("", 0, areaEventos.getLineEndOffset(0)); }
            catch (javax.swing.text.BadLocationException e) { }
        }
        areaEventos.setCaretPosition(areaEventos.getDocument().getLength());
    }

    /**
     * Cada celda del protocolo ocupa dos caracteres: ".." o el simbolo del jugador.
     * En pantalla cada posicion ocupa siempre ANCHO_CELDA_VISUAL caracteres:
     * "[xx] " si esta ocupada o " .   " si esta vacia. El espacio final funciona
     * como separador y evita que varios bloques consecutivos se vean pegados.
     */
    private void dibujar(String grid, String puntajes, String estado) {
        String[] rows = grid.split(";");
        StringBuilder sb = new StringBuilder();
        int columnasDibujadas = 0;
        for (String r : rows) {
            sb.append("\u2039!");
            int cantidad = r.length() / 2;
            columnasDibujadas = Math.max(columnasDibujadas, cantidad);
            for (int i = 0; i + 1 < r.length(); i += 2) {
                String celda = r.substring(i, i + 2);
                sb.append("..".equals(celda) ? " .   " : "[" + celda + "] ");
            }
            sb.append("!\u203A\n");
        }
        sb.append("\u2039!");
        for (int i = 0; i < ANCHO_CELDA_VISUAL * columnasDibujadas; i++) sb.append('=');
        sb.append("!\u203A\n");
        area.setText(sb.toString());
        area.setCaretPosition(0);

        String textoPuntos = puntajes.toUpperCase().replace("=", ": ").replace(",", "     ");
        areaPuntos.setText("[" + estado + "]  Puntos: " + textoPuntos);
        areaPuntos.setCaretPosition(0);
    }

    /** Dibuja la cola de piezas que el servidor asignara a continuacion (la 1a es la proxima). */
    private void dibujarCola(String nombres) {
        StringBuilder sb = new StringBuilder("SIGUIENTES PIEZAS\n");
        int n = 1;
        for (String nom : nombres.split(",")) {
            Pieza p = Pieza.porNombre(nom);
            if (p == null) continue;
            sb.append('\n').append(n == 1 ? "> " : "  ").append(n).append(") ").append(nom).append('\n');
            for (int r = 0; r < p.alto(); r++) {
                sb.append("   ");
                for (int c = 0; c < p.ancho(); c++) {
                    boolean hay = false;
                    for (int[] ce : p.celdas) if (ce[0] == r && ce[1] == c) hay = true;
                    sb.append(hay ? "[##] " : "     ");
                }
                sb.append('\n');
            }
            n++;
        }
        areaCola.setText(sb.toString());
        areaCola.setCaretPosition(0);
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new ClienteTetris().setVisible(true));
    }
}
