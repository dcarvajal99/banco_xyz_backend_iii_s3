#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Genera los juegos de datos de volumen que usa la medicion de rendimiento.

Los archivos de bank_legacy_data traen 1.000 filas, y una corrida sobre ellos dura menos de
un segundo. A esa escala el arranque de la JVM, el calentamiento del contenedor y el ruido del
sistema operativo pesan mas que la diferencia entre estrategias: la medicion de la semana 2
llego a tener una dispersion de 2,5x entre la muestra mas rapida y la mas lenta de una MISMA
configuracion. Ninguna cantidad de repeticiones arregla eso; hay que subir la senal.

Este script replica data/semana_3 N veces desplazando el identificador de cada bloque, de modo
que se conserva exactamente la mezcla de defectos (y por lo tanto la tasa de omision, que es
la que decide por que rama del flujo pasa la corrida) sin inventar duplicados ENTRE bloques
que falsearian el contador de filtrados.

Uso:  python3 scripts/generar_volumen.py 10 40
"""
import os
import sys

ORIGEN = 'data/semana_3'
ARCHIVOS = ['transacciones.csv', 'intereses.csv', 'cuentas_anuales.csv']


def desplazar(linea, bloque):
    """Suma bloque*1.000.000 a la primera columna si es numerica; si no, deja la fila intacta."""
    partes = linea.rstrip('\n').split(',')
    if partes and partes[0].strip().isdigit():
        partes[0] = str(int(partes[0]) + bloque * 1_000_000)
    return ','.join(partes) + '\n'


def generar(factor):
    destino = f'data/volumen_x{factor}'
    os.makedirs(destino, exist_ok=True)
    for archivo in ARCHIVOS:
        with open(f'{ORIGEN}/{archivo}', encoding='utf-8') as f:
            cabecera = f.readline()
            filas = f.readlines()
        with open(f'{destino}/{archivo}', 'w', encoding='utf-8') as salida:
            salida.write(cabecera)
            for bloque in range(factor):
                for fila in filas:
                    salida.write(desplazar(fila, bloque))
        total = factor * len(filas)
        print(f'  {destino}/{archivo}: {total} filas')


if __name__ == '__main__':
    factores = [int(a) for a in sys.argv[1:]] or [10]
    for factor in factores:
        print(f'== x{factor} ==')
        generar(factor)
