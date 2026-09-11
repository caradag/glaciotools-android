#!/usr/bin/env python3
"""Genera un CSV de prueba con la forma exacta que exporta GlacioTools.

Sirve para probar la carga y la visualizacion sin placa. Los datos no son ruido: la
temperatura tiene ciclo diario y tendencia estacional, la bateria baja de verdad siguiendo
el consumo, y hay huecos y lecturas fallidas -- que es lo que de verdad rompe un grafico.
"""
import argparse, math, random
from datetime import datetime, timedelta

# Columnas del signature 0x101F: los cuatro canales de serie mas una sonda DS18B20.
COLUMNS = ["Volt", "Temp", "RH", "HAtemp", "DS0"]
DECIMALS = {"Volt": 2, "Temp": 2, "RH": 1, "HAtemp": 2, "DS0": 2}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--days", type=float, default=45.0)
    ap.add_argument("--interval", type=int, default=600, help="segundos entre registros")
    ap.add_argument("--start", default="2026-06-15 00:00:00")
    ap.add_argument("--seed", type=int, default=7)
    ap.add_argument("-o", "--output", default="glaciotools_ejemplo.csv")
    a = ap.parse_args()

    rnd = random.Random(a.seed)
    t0 = datetime.strptime(a.start, "%Y-%m-%d %H:%M:%S")
    n = int(a.days * 86400 / a.interval)

    # La bateria se descarga a lo largo del periodo. El rango cubre buena parte de la curva
    # alcalina del firmware, para que la estimacion tenga con que trabajar.
    v_ini, v_fin = 1.545, 1.245

    rows = []
    for i in range(n):
        t = t0 + timedelta(seconds=i * a.interval)
        day = i * a.interval / 86400.0
        hour = t.hour + t.minute / 60.0

        # Ciclo diario mas tendencia estacional (invierno austral que se va suavizando).
        base = -4.5 + 3.0 * day / max(a.days, 1e-9)
        daily = 4.2 * math.sin((hour - 9.0) / 24.0 * 2 * math.pi)
        temp = base + daily + rnd.gauss(0, 0.45)

        # El aire va por delante del sensor enterrado, que amortigua y retrasa el ciclo.
        ha = base + 0.35 * daily + rnd.gauss(0, 0.25)
        # La sonda DS18B20 va en el hielo: casi isoterma y siempre bajo cero.
        ds = min(-0.05, base * 0.25 - 0.4 + rnd.gauss(0, 0.08))

        # Humedad alta y anticorrelacionada con la temperatura, saturando cerca de 100.
        rh = max(38.0, min(99.5, 88.0 - 2.6 * daily + rnd.gauss(0, 3.0)))

        # El voltaje cae con la descarga y ademas depende de la temperatura: por eso la
        # estimacion de bateria hace una regresion y no compara dos puntos.
        volt = (v_ini + (v_fin - v_ini) * (day / max(a.days, 1e-9))
                + 0.004 * temp + rnd.gauss(0, 0.003))

        vals = {"Volt": volt, "Temp": temp, "RH": rh, "HAtemp": ha, "DS0": ds}

        # Un 0,4% de lecturas fallidas del sensor de temperatura y humedad, como en terreno.
        if rnd.random() < 0.004:
            vals["Temp"] = None
            vals["RH"] = None
        # Y de vez en cuando la sonda de 1-Wire no contesta.
        if rnd.random() < 0.002:
            vals["DS0"] = None

        # Un hueco de seis horas a mitad del periodo: el logger estuvo apagado. El grafico
        # tiene que dejarlo como hueco y no unir los dos extremos con una recta.
        if a.days / 2 < day < a.days / 2 + 0.25:
            continue

        rows.append((t, vals))

    with open(a.output, "w", encoding="utf-8") as f:
        f.write("Time," + ",".join(COLUMNS) + "\n")
        for t, vals in rows:
            cells = []
            for c in COLUMNS:
                v = vals[c]
                if v is None:
                    cells.append("NaN")
                else:
                    # Se redondea ANTES de formatear y se le suma cero, para no escribir
                    # nunca "-0.00": el cero negativo no lo produce el decodificador del
                    # firmware y solo sirve para confundir.
                    cells.append(f"{round(v, DECIMALS[c]) + 0.0:.{DECIMALS[c]}f}")
            f.write(t.strftime("%Y-%m-%d %H:%M:%S") + "," + ",".join(cells) + "\n")

    print(f"{a.output}: {len(rows)} registros, "
          f"{rows[0][0]:%Y-%m-%d} a {rows[-1][0]:%Y-%m-%d}")


if __name__ == "__main__":
    main()
