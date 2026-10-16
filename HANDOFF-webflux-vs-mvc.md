# HANDOFF — WebFlux vs Spring MVC (con y sin virtual threads), medido con k6

Contexto para Claude Code en IntelliJ/VS Code. Jaime viene de repasar concurrencia a fondo
(race conditions, ExecutorService, virtual threads con un sleep de 10 s, CompletableFuture
con caso real medido con k6, incluyendo la lección del warm-up). Hoy ha preguntado qué es
Spring WebFlux. Se le explicó con la analogía del restaurante (MVC = un camarero por
cliente que se queda esperando; WebFlux = pocos camareros que no esperan parados) y se le
contó que `Mono` y `Flux` son como un `CompletableFuture` que ya conoce. Objetivo de esta
sesión: que VEA con sus propios números cuándo WebFlux ayuda, cuándo no, y por qué los
virtual threads de Java 21 compiten con él.

Proyecto NUEVO y aislado (NO tocar section_17). Se empieza DE CERO: el código de una
sesión anterior se perdió, así que no asumir que existe nada previo salvo los esqueletos
que Jaime acaba de generar con Spring Initializr.
Carpeta de trabajo: `C:\Users\User\spring_boot\comparandoMvc\`, abierta en IntelliJ,
con dos apps independientes dentro: `mvc-app` y `webflux-app`, abiertas las dos en la
MISMA ventana (con la opción "Attach"). Cada app tiene su propio `pom.xml`; no hay pom
padre. La carpeta `k6` se crea dentro de `comparandoMvc`, al lado de las dos apps.
Este mismo archivo `.md` está en la raíz de `comparandoMvc`.
Aunque estén las dos apps abiertas a la vez, durante las mediciones se ejecuta SOLO UNA
app cada vez, para que no compitan por la CPU.

## MUY IMPORTANTE — estilo de esta sesión
- Jaime tiene nivel técnico bajo en este tema: NO dar por supuesto que conoce el vocabulario
  de programación reactiva. Explicar cada término nuevo con una analogía simple antes de usarlo.
- UN paso cada vez. Después de cada paso, parar y esperar su respuesta antes de seguir
  (lo ha pedido expresamente varias veces: si se le dan varios pasos a la vez, no aprende).
- Que EJECUTE ÉL los comandos en su terminal y pegue lo que sale. No ejecutar por él.
- Dar los comandos exactos y los enlaces directos a lo que tenga que consultar.
- Explicar ANTES de escribir código y DESPUÉS interpretar el resultado en lenguaje llano.
- Lo que se descarte en la sesión se queda descartado: no volver a proponerlo.
- Honestidad: si los números salen distintos de lo esperado, contarlo tal cual y buscar el
  porqué con él. No maquillar resultados.

## Qué se va a construir
Dos mini-apps Spring Boot que Jaime YA ha creado con Spring Initializr dentro de IntelliJ
(Java 21, Maven, Spring Boot 4.x, Lombok marcado en las dos aunque el código de este
ejercicio no lo usa). AL EMPEZAR, comprobar que existen en las rutas correctas, que cada
`pom.xml` tiene el starter esperado y que compilan:

1. `mvc-app` (puerto 8081): Spring MVC clásico. Dependencia: Spring Web
   (`spring-boot-starter-webmvc` en Boot 4).
2. `webflux-app` (puerto 8082): WebFlux. Dependencia: Spring Reactive Web
   (`spring-boot-starter-webflux`).

IMPORTANTE: son proyectos SEPARADOS. Si se meten los dos starters en el mismo proyecto,
Spring Boot arranca MVC y WebFlux queda ignorado, y el experimento no vale.

Los dos exponen un endpoint `/slow?ms=200` que simula una llamada lenta a otro servicio
(como cuando `accounts` espera a `cards`/`loans`) y devuelve el nombre del hilo que atendió
la petición.

## PASO 0 — Preparar el entorno (sin esto los números no valen)
1. Parar el clúster de EazyBank y los contenedores de Docker que no hagan falta
   (`docker ps` para ver qué hay corriendo). Si no, compiten con la prueba por CPU y memoria.
2. Cerrar programas pesados (navegador con muchas pestañas, etc.).
3. Comprobar que k6 está instalado: `k6 version`.
4. Explicar a Jaime que k6 y las apps van a correr en el MISMO ordenador, así que los
   números absolutos dependen de su máquina. Lo que importa es la COMPARACIÓN entre los tres
   casos, no el valor exacto.

## PASO 1 — Explicar la idea antes de tocar código (sin código)
Con la analogía del restaurante, confirmar que Jaime lo explica con sus palabras:
- Spring MVC: un hilo (camarero) por petición; si la petición espera, el hilo espera parado.
- Tomcat tiene un límite de hilos (por defecto 200). Más peticiones simultáneas que hilos
  = las demás hacen cola.
- WebFlux: muy pocos hilos que nunca se quedan esperando; cuando algo tarda, atienden otra cosa.
- Virtual threads (los de su demo del sleep de 10 s): hilos baratísimos, así que MVC puede
  tener miles sin problema, sin cambiar la forma de programar.
Pregunta de comprobación: "¿qué crees que pasará si 300 usuarios llaman a la vez a un
endpoint que tarda 200 ms, y el servidor solo tiene 50 hilos?"

## PASO 2 — App MVC y endpoint lento
`mvc-app`, en `src/main/resources/application.properties`:
```
server.port=8081
server.tomcat.threads.max=50
```
(Se baja el límite a 50 a propósito: así el efecto se ve con poca carga.)

Controlador:
```java
@RestController
class SlowController {

    @GetMapping("/slow")
    Map<String, String> slow(@RequestParam(defaultValue = "200") long ms)
            throws InterruptedException {
        Thread.sleep(ms);
        return Map.of("thread", Thread.currentThread().toString());
    }
}
```
Arrancar la app y probar una petición suelta:
`curl "http://localhost:8081/slow?ms=200"`
Qué fijarse: el nombre del hilo será algo como `http-nio-8081-exec-3` (un hilo normal de Tomcat).

## PASO 3 — App WebFlux y endpoint lento
`webflux-app`, `application.properties`: `server.port=8082`

```java
@RestController
class SlowController {

    @GetMapping("/slow")
    Mono<Map<String, String>> slow(@RequestParam(defaultValue = "200") long ms) {
        return Mono.delay(Duration.ofMillis(ms))
                .map(t -> Map.of("thread", Thread.currentThread().toString()));
    }
}
```
Explicar con calma qué hace `Mono.delay`: "avísame dentro de 200 ms", SIN dejar ningún hilo
parado esperando (es lo contrario de `Thread.sleep`).
Probar: `curl "http://localhost:8082/slow?ms=200"`
Qué fijarse: el hilo tiene un nombre distinto (tipo `parallel-1`), no `http-nio-...`.

## PASO 4 — Predicción antes de medir (muy importante pedagógicamente)
Antes de ejecutar k6, que Jaime ESCRIBA su predicción para estos tres casos con 300 usuarios
simultáneos y 200 ms de espera: (A) MVC con 50 hilos, (B) MVC con virtual threads,
(C) WebFlux. ¿Cuántas peticiones por segundo aguantará cada uno, más o menos?
Orientación para el agente (NO decírsela todavía): el máximo teórico con 300 usuarios y
200 ms es unas 1.500 peticiones/s. MVC con 50 hilos debería quedarse cerca de 250
(50 hilos / 0,2 s).

## PASO 5 — El script de k6
Crear `k6/loadtest.js`. Ya conoce el patrón de calentamiento + medición, usarlo igual:
```javascript
import http from 'k6/http';
import { check } from 'k6';

const BASE = __ENV.BASE_URL || 'http://localhost:8081';
const RUTA = __ENV.PATH_URL || '/slow?ms=200';   // NO llamarla PATH: choca con la variable PATH de Windows

export const options = {
    scenarios: {
        calentamiento: { executor: 'constant-vus', vus: 100, duration: '10s', exec: 'peticion' },
        medicion: { executor: 'constant-vus', vus: 300, duration: '30s', startTime: '10s', exec: 'peticion' },
    },
    thresholds: {
        'http_req_failed{scenario:medicion}': ['rate<0.01'],
        'http_req_duration{scenario:medicion}': ['p(95)<1000'],
    },
};

export function peticion() {
    const res = http.get(`${BASE}${RUTA}`);
    check(res, { 'status 200': (r) => r.status === 200 });
}
```
Explicar qué hace cada escenario y por qué hay calentamiento (lo aprendió con el p95 de
1,06 s que bajó a 37 ms).
Comando base: `k6 run -e BASE_URL=http://localhost:8081 -e PATH_URL="/slow?ms=200" k6/loadtest.js`
De los resultados anotar: `http_reqs` (peticiones por segundo), `http_req_duration`
(med y p(95)) y el porcentaje de errores, SOLO de la fase de medición.

## PASO 6 — Los tres casos a medir (uno detrás de otro, parando la app anterior)
Caso A — MVC clásico (50 hilos). Arrancar `mvc-app` normal y lanzar k6 contra el 8081.

Caso B — MVC con virtual threads. Parar la app y arrancarla así (PowerShell):
```
$env:SPRING_THREADS_VIRTUAL_ENABLED="true"; ./mvnw spring-boot:run
```
Comprobar con curl que ahora el hilo se llama `VirtualThread[...]`. Lanzar k6 contra el 8081.
Al terminar quitar la variable: `Remove-Item Env:SPRING_THREADS_VIRTUAL_ENABLED`

Caso C — WebFlux. Arrancar `webflux-app` y lanzar k6 contra el 8082 con `/slow?ms=200`.

Rellenar con Jaime esta tabla con SUS números:
| Caso | Peticiones/s | Mediana | p(95) | Errores |
|------|-------------|---------|-------|---------|
| A: MVC 50 hilos | | | | |
| B: MVC + virtual threads | | | | |
| C: WebFlux | | | | |
Comparar con la predicción del Paso 4 y comentar las diferencias sin dramatizar.

## PASO 7 — La trampa de WebFlux (el aprendizaje más importante)
Añadir a `webflux-app` dos endpoints más:
```java
// MAL: bloquea el hilo, que en WebFlux hay muy pocos
@GetMapping("/slow-blocking")
Mono<Map<String, String>> slowBlocking(@RequestParam(defaultValue = "200") long ms)
        throws InterruptedException {
    Thread.sleep(ms);
    return Mono.just(Map.of("thread", Thread.currentThread().toString()));
}

// ARREGLADO: el trabajo que bloquea se manda a otro grupo de hilos pensado para eso
@GetMapping("/slow-fixed")
Mono<Map<String, String>> slowFixed(@RequestParam(defaultValue = "200") long ms) {
    return Mono.fromCallable(() -> {
                Thread.sleep(ms);
                return Map.of("thread", Thread.currentThread().toString());
            })
            .subscribeOn(Schedulers.boundedElastic());
}
```
Medir con k6 `PATH_URL="/slow-blocking?ms=200"` y después `"/slow-fixed?ms=200"`.
Resultado esperado: `/slow-blocking` se hunde (solo hay tantos hilos como núcleos tiene
su CPU, y todos se quedan parados), y `/slow-fixed` se recupera, aunque con un tope (el
grupo `boundedElastic` también tiene un máximo de hilos).
Explicar con analogía: es como poner a los pocos camareros a esperar parados en la cocina.
Conectar con lo que ya se le dijo: JPA/Hibernate es bloqueante, así que meterlo tal cual en
WebFlux produce justo este problema. La alternativa reactiva se llama R2DBC y NO se toca en
esta sesión (descartado por ahora).

## PASO 8 — Conclusiones, con sus números delante
Que Jaime las escriba con sus palabras (un README corto en el proyecto sirve):
1. ¿Qué diferencia hubo entre A y B? (mismo estilo de código, solo cambia una propiedad)
2. ¿Qué diferencia hubo entre B y C? ¿Compensa programar en estilo reactivo para este caso?
3. ¿Qué pasó con `/slow-blocking` y por qué?
4. ¿Cuándo elegiría cada opción para un servicio como `accounts`?
Resultado esperado (si sus números lo confirman, si no, contarlo como salga): B y C
quedan parecidos en este tipo de prueba, y A queda muy por detrás.

## Frases para entrevista (que pueda decir con sus propios datos)
- "Medí tres variantes con k6: MVC con 50 hilos, MVC con virtual threads y WebFlux. Con
  300 usuarios y una espera de 200 ms, MVC clásico quedó limitado por su pool de hilos, y
  tanto virtual threads como WebFlux se acercaron al máximo teórico."
- "Para servicios tipo CRUD con base de datos bloqueante (JPA), prefiero MVC con virtual
  threads: misma ventaja, código más simple. WebFlux lo reservo para streaming o cuando toda
  la cadena es no bloqueante."
- "Comprobé en vivo que bloquear dentro de WebFlux hunde el rendimiento: hay muy pocos
  hilos y se quedan parados."

## Criterio de "hecho"
- [ ] Dos apps separadas funcionando (8081 y 8082) y probadas con curl.
- [ ] Predicción escrita ANTES de medir.
- [ ] Tabla con los tres casos medidos con k6 (solo fase de medición).
- [ ] Probados `/slow-blocking` y `/slow-fixed` y entendida la diferencia.
- [ ] Conclusiones escritas por Jaime con sus palabras.
- [ ] (Opcional) README corto y repo público en GitHub. NO añadirlo al CV todavía: los CVs
      están ajustados a 2 páginas, se decidirá más adelante.

## Qué NO hacer
- No tocar `section_17` ni EazyBank.
- No meter R2DBC, bases de datos ni Spring Data en este ejercicio.
- No mezclar `webmvc` y `webflux` en el mismo proyecto.
- No ejecutar los comandos por Jaime ni dar varios pasos seguidos sin esperar su respuesta.
