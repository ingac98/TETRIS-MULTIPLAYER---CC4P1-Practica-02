# TETRIS MIX MULTIPLAYER - CC4P1 Practica 02

Proyecto desarrollado en Java 8+ utilizando exclusivamente el SDK estandar de Java (java.net.Socket, Swing, java.lang.management). Sin WebSockets ni librerias externas.

---

## Compilacion y Ejecucion

### Compilar (desde la carpeta raiz TetrisMix)
```bash
javac -encoding UTF-8 -d classes src/tetris/*.java
```

### Ejecutar el Juego

#### 1) Servidor
```bash
java -cp classes tetris.ServidorTetris
```
* Campos: Puerto, fil, col, ms/caida. Pulsar "Iniciar servidor".
* El panel de dimensionamiento calcula columnas minimas/recomendadas segun los jugadores esperados.
* Con los jugadores conectados, pulsar "Iniciar partida".

#### 2) Cliente real (uno por jugador)
```bash
java -cp classes tetris.ClienteTetris
```
* Indicar IP y puerto del servidor y pulsar "Connect".

---

## Controles

* Flechas izquierda/derecha/abajo: Movimiento
* Flecha arriba o boton ^: Caida rapida
* G1 (Z): Giro antihorario
* G2 (X): Giro horario

---

## Nuevas Partidas sin Reiniciar el Servidor

Filas, columnas y ms/caida quedan bloqueados unicamente mientras una partida esta en curso. Al terminar, vuelven a habilitarse. Los clientes pueden permanecer conectados.

* "Preparar tablero": Aplica fil/col, limpia tablero/puntajes/piezas y deja todo listo para otra partida.
* "Usar recomendacion": Coloca el ancho recomendado y, si el servidor ya esta encendido, prepara ese tablero.
* "Iniciar partida": Tambien aplica automaticamente las dimensiones visibles antes de comenzar.

Por seguridad, no se permite redimensionar durante una partida.

---

## Identificadores [xx]

Cada jugador usa un simbolo visual fijo de DOS caracteres; cada bloque se dibuja como [xx].

Los primeros 286 identificadores priorizan lectura y diferenciacion:
1. 1..26: aa, bb, cc, ..., zz
2. 27..286: a0, b0, ..., z0, a1, ..., z9

Luego se usan las 650 combinaciones letra-letra restantes (ab, ac, ..., zy), pensadas sobre todo para pruebas de carga. Total: $26 \times 36 = 936$ identificadores visuales unicos simultaneos.

* Bloque fijo: [a7]
* Pieza activa: [A7]

Cada posicion visual reserva exactamente 5 caracteres: "[xx] " o " .   ". Esto conserva alineacion y separacion entre bloques.

---

## Dimensionamiento del Tablero

El ancho maximo de aparicion de una pieza es 3 columnas. El servidor muestra:
* minimo seguro = $3 \times \text{jugadores}$
* recomendado = $4 \times \text{jugadores}$

Ejemplo: 100 jugadores -> minimo 300 columnas, recomendado 400.

El numero del campo "Jugadores aprox." es solo una ayuda y NO limita conexiones. Antes de iniciar se valida tambien el numero real de jugadores conectados.

Limite tecnico preventivo: 10000 columnas. Limite visual [xx]: 936 jugadores simultaneos.

---

## Pruebas de Rendimiento Integradas en el Servidor

Se dejaron SOLO las dos metricas acordadas:
A) tiempo de tick();
B) memoria JVM + numero de hilos vivos del servidor.

En el panel "Pruebas de rendimiento (servidor)" aparecen:
* Calentamiento (s): por defecto 10.
* Medicion (s): por defecto 120.
* Boton "Iniciar medicion".

### Procedimiento Recomendado por Cada Configuracion
1. Conectar la cantidad deseada de jugadores.
2. Iniciar la partida y hacer que TODOS jueguen.
3. Pulsar "Iniciar medicion".
4. Esperar 10 s de calentamiento + 120 s de medicion (o los valores configurados).
5. El servidor guarda automaticamente los resultados en la carpeta resultados/.

### Archivos Generados
* `resultados/detalle_metricas_FECHA.csv`: Registros individuales de TICK y muestras de RECURSOS.
* `resultados/resumen_metricas.csv`: Una fila acumulada por experimento con jugadores, dimensiones, tiempo de medicion, tick promedio/min/max, memoria promedio/maxima e hilos promedio/maximos.

### Detalles Tecnicos de Medicion
* Timer de TICK: En ServidorTetris.java las lineas estan marcadas con comentarios EN MAYUSCULA. El timer inicia ANTES de solicitar synchronized(tablero), por lo que incluye: espera por contencion + ejecucion real de tablero.tick(). Termina inmediatamente despues de tick(), antes de construir/encolar el STATE. Asi la metrica representa la caida grupal y su contencion, no el envio por red.
* Memoria / Hilos: Se toma aproximadamente una muestra por segundo durante la ventana de medicion.
  * Memoria: heap usada por la JVM del servidor (totalMemory - freeMemory).
  * Hilos: hilos vivos de la JVM segun ThreadMXBean; incluye hilos internos de Java.
  * El muestreo usa el MISMO ScheduledExecutorService de gravedad para no crear un hilo adicional exclusivamente para medir y alterar artificialmente el conteo.

---

## Experimento 1 - Clientes Reales

Probar, por ejemplo: 1, 2, 3, 4, 5, 6 y 7 jugadores reales.

Todos deben jugar normalmente durante la medicion. Para cada cantidad registrar el resumen CSV.
Las graficas principales seran:
* jugadores vs tick promedio (ms)
* jugadores vs memoria promedio/maxima (MB)
* jugadores vs hilos promedio/maximos

---

## Experimento 2 - Clientes Simulados

ClienteCarga.java crea clientes sin Swing, consume los STATE y envia movimientos automaticos.
Todos los bots "juegan"; por defecto cada uno envia un movimiento cada 1000 ms.

Uso:
```bash
java -cp classes tetris.ClienteCarga IP PUERTO CLIENTES [INTERVALO_MS]
```

Ejemplos:
```bash
java -cp classes tetris.ClienteCarga 127.0.0.1 5684 10 1000
java -cp classes tetris.ClienteCarga 192.168.1.10 5684 100 1000
java -cp classes tetris.ClienteCarga 192.168.1.10 5684 300 1000
```

Serie sugerida: 10, 20, 30, 40, 50, 100, 150, 200, 250, 300 clientes.

### Orden Correcto
1. Configurar en el servidor el numero aproximado y usar la recomendacion de columnas.
2. Iniciar el servidor.
3. Ejecutar ClienteCarga y esperar a que se conecten todos los bots.
4. Pulsar "Iniciar partida" en el servidor.
5. Pulsar "Iniciar medicion".
6. Esperar que termine la ventana de medicion y conservar los CSV.
7. ENTER en ClienteCarga para desconectar los bots.

IMPORTANTE: para carga, dejar desmarcada la opcion "Tamano lo define el primer cliente". Idealmente ClienteCarga debe ejecutarse en otra laptop para no competir por CPU/RAM con el servidor. Si se ejecuta en la misma laptop, indicarlo en el informe porque puede influir en el tiempo de tick.

---

## Arquitectura / Concurrencia

* Un hilo aceptador recibe conexiones.
* Cada cliente tiene en el servidor un hilo lector y un hilo escritor.
* Un ScheduledExecutorService de un hilo ejecuta gravedad y muestreo de recursos.
* synchronized de Tablero protege el estado compartido.
* ColaSalida realiza la escritura TCP fuera del monitor y conserva solo el ultimo STATE pendiente.

Con $n$ clientes, la aplicacion del servidor crea aproximadamente $2n + 3$ hilos propios, ademas de los hilos internos de la JVM. La prueba de hilos permite observar este crecimiento.

---

## Reglas / Protocolo

Reglas implementadas: tablero unico compartido y replicado; piezas Cuadro (1x1, 2x2), T, I (2 y 3), L, Y; rotaciones; sin superposicion; eliminacion de lineas; puntuacion; fin de partida; caida grupal de piezas activas apoyadas.

### Protocolo por Linea
* Cliente -> LEFT | RIGHT | DOWN | UP | ROT1 | ROT2 | HELLO fil col
* Servidor -> ID|id|simbolo, SIZE|fil|col, MSG|texto, ALERT|texto, STATE|filas;separadas|puntajes|LOBBY/PLAY/OVER|cola

Cada celda de STATE ocupa exactamente 2 caracteres:
* `..` = vacia
* `aa` = bloque fijo
* `AA` = bloque activo

### Desconexiones
Al salir un jugador se retira su pieza activa y permanecen sus bloques fijos.
Si todos abandonan durante una partida: "Todos los jugadores se fueron.".
No se aceptan jugadores nuevos mientras una partida esta en curso.

---

## Red Local / Hamachi

Este codigo no depende de Hamachi. Usa exclusivamente ServerSocket/Socket de Java.
En LAN se usa la IPv4 del servidor. Con Hamachi puede usarse la IP virtual del servidor sin cambiar ninguna linea del programa; Hamachi actua debajo de la aplicacion como red virtual.
Para el informe conviene distinguir pruebas LAN fisicas de pruebas realizadas por VPN/Hamachi.