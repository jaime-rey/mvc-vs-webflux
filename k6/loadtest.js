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