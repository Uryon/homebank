package com.homebank.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.Mockito.mock;

/**
 * ServiceExport lance un script externe : le "script Node" est remplace ici
 * par un petit programme Java (lanceur de fichier source, java Script.java),
 * execute par la JVM courante, pour verifier les cas de sortie reels sans
 * dependre de Node/Playwright.
 */
class ServiceExportTest {

    @TempDir
    Path tempDir;

    private static final String JAVA = ProcessHandle.current().info().command().orElse("java");

    private ServiceExport service(Path script, long delaiMaxSecondes) {
        return new ServiceExport(tempDir.toString(), "http://localhost/", script.toString(), JAVA,
                delaiMaxSecondes, mock(ServiceDernierImport.class));
    }

    private Path script(String mainBody) throws IOException {
        Path file = tempDir.resolve("Script.java");
        Files.writeString(file, "public class Script { public static void main(String[] a) throws Exception { "
                + mainBody + " } }", StandardCharsets.UTF_8);
        return file;
    }

    @Test
    void exporter_reussitQuandLeScriptSeTermineNormalement() throws IOException {
        ServiceExport service = service(script("System.out.println(\"PDF ecrit\");"), 60);

        assertThatCode(service::exporterTableauDeBord).doesNotThrowAnyException();
    }

    @Test
    void exporter_remonteLaSortieDuScriptEnCasDEchec() throws IOException {
        ServiceExport service = service(script("System.out.println(\"page introuvable\"); System.exit(2);"), 60);

        assertThatThrownBy(service::exporterTableauDeBord)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("page introuvable");
    }

    @Test
    void exporter_arreteUnScriptFigeAuBoutDuDelaiSansBloquerLaRequete() throws IOException {
        // Le script ecrit puis reste bloque en gardant sa sortie ouverte : lire
        // cette sortie en direct bloquait jusqu'a la fin du script, rendant le
        // delai inoperant.
        ServiceExport service = service(script("System.out.println(\"demarre\"); Thread.sleep(120_000);"), 3);

        assertTimeoutPreemptively(Duration.ofSeconds(30), () ->
                assertThatThrownBy(service::exporterTableauDeBord)
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("delai depasse"));
    }

    @Test
    void exporter_refuseSansDossierDeSortieConfigure() {
        ServiceExport service = new ServiceExport(" ", "http://localhost/", "script.mjs", JAVA, 30,
                mock(ServiceDernierImport.class));

        assertThatThrownBy(service::exporterTableauDeBord).isInstanceOf(IllegalStateException.class);
    }
}
