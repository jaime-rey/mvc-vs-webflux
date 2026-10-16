# MVC vs WebFlux (con y sin virtual threads), medido con k6

Pequeño experimento para comparar tres formas de atender peticiones HTTP en
Spring Boot 4 con Java 21:

1. **Spring MVC clásico** (Tomcat, pool de hilos fijo).
2. **Spring MVC con virtual threads** (Java 21).
3. **Spring WebFlux** (Netty, reactivo).

Y, en un segundo experimento, el efecto del **pool de conexiones** a la base
de datos (HikariCP con JPA frente a R2DBC).

## Estructura

- `mvc-app/` — Spring MVC, puerto 8081.
- `webflux-app/` — WebFlux, puerto 8082.
- `k6/loadtest.js` — script de carga: 10 s de calentamiento (100 VUs) + 30 s
  de medición (300 VUs).

## Metodología

- 300 usuarios virtuales concurrentes contra un endpoint que simula una
  llamada lenta (`Thread.sleep` / `Mono.delay` de 200 ms) o una consulta a
  base de datos de 20 ms.
- Solo una app arrancada cada vez.
- Se anotan únicamente los números de la fase de medición.

Comando base:

```powershell
k6 run -e BASE_URL=http://localhost:8081 -e PATH_URL="/slow?ms=200" k6/loadtest.js
```

Para activar virtual threads en MVC:

```powershell
$env:SPRING_THREADS_VIRTUAL_ENABLED="true"
./mvnw spring-boot:run
```

## Resultados — experimento 1: solo esperar (200 ms, sin BD)

Techo teórico con 300 usuarios y 200 ms: 1 500 pet./s.

| Caso | Endpoint | Pet./s | Mediana | p(95) | Errores |
|------|----------|-------:|--------:|------:|--------:|
| A: MVC 50 hilos | `/slow` | ~250 | 1,21 s | 1,24 s | 2,89 % |
| B: MVC + virtual threads | `/slow` | ~1 460 | 203 ms | 217 ms | 1,16 % |
| C: WebFlux | `/slow` | ~1 440 | 205 ms | 217 ms | 0,19 % |
| WebFlux bloqueando | `/slow-blocking` | ~123 | 1,85 s | 2,69 s | 94,5 % |
| WebFlux `boundedElastic` | `/slow-fixed` | ~1 157 | 214 ms | 418 ms | 0,22 % |

## Resultados — experimento 2: base de datos H2 (consulta 20 ms, pool 10)

Techo teórico con pool de 10 y 20 ms: 500 pet./s.

| Caso | Endpoint | Pet./s | Mediana | p(95) | Errores |
|------|----------|-------:|--------:|------:|--------:|
| D: MVC + VT + JPA (Hikari) | `/db` | ~337 | 928 ms | 935 ms | 1,03 % |
| E: WebFlux + R2DBC | `/db` | ~334 | 927 ms | 934 ms | 0,79 % |

## Lecciones

1. Sin base de datos, MVC con virtual threads iguala a WebFlux con mucho menos
   código. MVC clásico queda muy por detrás por su pool de hilos.
2. Bloquear en WebFlux hunde el servicio (`/slow-blocking`: 94 % de errores).
   JPA/Hibernate son bloqueantes, así que meterlos tal cual en WebFlux
   reproduce exactamente este problema.
3. Con base de datos, el cuello de botella se desplaza de los hilos al **pool
   de conexiones**. Virtual threads y R2DBC dan resultados prácticamente
   idénticos (337 vs 334 pet./s): manda el pool, no el tipo de hilo.
4. Para un CRUD con JPA (p. ej. `accounts` de un microservicio), **MVC con
   virtual threads** combina rendimiento equivalente a WebFlux, código normal
   y sin la trampa de bloquear por accidente.

## Qué no mide este experimento

- Cada caso se midió una sola vez; los números son indicativos.
- Los errores aparecen en una ráfaga al inicio de la medición; probablemente
  se deben al salto brusco a 300 VUs, no se investigaron a fondo.
- H2 en memoria no se comporta igual que PostgreSQL o MySQL reales. Con una
  BD "pesada", subir el pool más allá de ~20 suele empeorar las cosas.
- "Pinning" de virtual threads (si un hilo virtual entra en `synchronized`,
  queda pegado al hilo real) no se midió. Existe, hay que saberlo.

## Entorno

- Windows 11, 24 núcleos.
- Java 21 (Corretto 21.0.12).
- Spring Boot 4.1.1.
- k6 v2.2.0.
- H2 en memoria.
