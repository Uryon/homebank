package com.homebank.config;

import com.homebank.service.ServiceSauvegarde;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.task.TaskExecutor;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** La sauvegarde du jour part en tâche de fond, jamais pendant l'entraînement, et son échec ne bloque pas l'appli. */
@ExtendWith(MockitoExtension.class)
class SauvegardeAuDemarrageTest {

    /** Exécute la tâche immédiatement, sur le fil du test. */
    private static final TaskExecutor IMMEDIAT = Runnable::run;

    @Mock
    private ServiceSauvegarde sauvegardeService;

    @Test
    void lanceLaSauvegardeDuJour() {
        new SauvegardeAuDemarrage(sauvegardeService, IMMEDIAT, false).run(null);
        verify(sauvegardeService).sauvegardeAutomatique();
    }

    @Test
    void neSauvegardePasPendantLEntrainementDuDemarrageRapide() {
        new SauvegardeAuDemarrage(sauvegardeService, IMMEDIAT, true).run(null);
        verify(sauvegardeService, never()).sauvegardeAutomatique();
    }

    @Test
    void unEchecNeBloquePasLeDemarrage() {
        when(sauvegardeService.sauvegardeAutomatique()).thenThrow(new IllegalStateException("disque plein"));
        SauvegardeAuDemarrage demarrage = new SauvegardeAuDemarrage(sauvegardeService, IMMEDIAT, false);
        assertThatCode(() -> demarrage.run(null)).doesNotThrowAnyException();
    }
}
