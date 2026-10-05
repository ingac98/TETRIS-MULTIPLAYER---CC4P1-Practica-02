package tetris;

import java.util.LinkedList;

/** Conserva los avisos en orden y solo el ultimo STATE pendiente. */
final class ColaSalida {
    private final LinkedList<String> pendientes = new LinkedList<String>();
    private String estadoPendiente;
    private boolean cerrada;

    synchronized void ofrecer(String mensaje) {
        if (cerrada) return;
        if (mensaje.startsWith("STATE|")) {
            if (estadoPendiente != null) pendientes.remove(estadoPendiente);
            estadoPendiente = mensaje;
        }
        pendientes.addLast(mensaje);
        notifyAll();
    }

    synchronized String tomar() throws InterruptedException {
        while (pendientes.isEmpty() && !cerrada) wait();
        if (pendientes.isEmpty()) return null;
        String mensaje = pendientes.removeFirst();
        if (mensaje.startsWith("STATE|")) estadoPendiente = null;
        return mensaje;
    }

    synchronized void cerrar(boolean descartar) {
        cerrada = true;
        if (descartar) { pendientes.clear(); estadoPendiente = null; }
        notifyAll();
    }
}
