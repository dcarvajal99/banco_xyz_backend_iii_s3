package com.bancoxyz;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Aplicacion batch de migracion del sistema legacy del Banco XYZ.
 *
 * <p>No expone puertos ni endpoints: es un proceso de linea de comandos que arranca,
 * ejecuta el Job solicitado y termina, igual que los shell scripts que reemplaza. El
 * codigo de salida refleja el resultado de la migracion, de modo que el planificador de
 * tareas del banco (cron, Control-M) puede encadenar o alertar segun corresponda.</p>
 *
 * @see com.bancoxyz.config.LanzadorDeJobs
 */
@SpringBootApplication
public class BancoXyzBatchApplication {

    public static void main(String[] args) {
        // SpringApplication.exit consulta al ExitCodeGenerator (LanzadorDeJobs) antes de cerrar.
        System.exit(SpringApplication.exit(SpringApplication.run(BancoXyzBatchApplication.class, args)));
    }
}
