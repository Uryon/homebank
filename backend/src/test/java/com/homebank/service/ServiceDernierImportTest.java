package com.homebank.service;

import com.homebank.model.DernierImport;
import com.homebank.repository.DepotDernierImport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ServiceDernierImportTest {

    @Mock
    private DepotDernierImport repository;

    private ServiceDernierImport service;

    @BeforeEach
    void setUp() {
        service = new ServiceDernierImport(repository);
    }

    @Test
    void dateDernierImport_aucunImportEnregistre_renvoieNull() {
        when(repository.findById(1L)).thenReturn(Optional.empty());

        assertThat(service.dateDernierImport()).isNull();
    }

    @Test
    void dateDernierImport_importDejaEnregistre_renvoieSaDate() {
        DernierImport existant = new DernierImport();
        existant.setId(1L);
        existant.setDateHeure(LocalDateTime.of(2024, 3, 1, 10, 30));
        when(repository.findById(1L)).thenReturn(Optional.of(existant));

        assertThat(service.dateDernierImport()).isEqualTo(LocalDateTime.of(2024, 3, 1, 10, 30));
    }

    @Test
    void enregistrerImport_aucuneLigneExistante_creeLaLigneUniqueAvecLaDateActuelle() {
        when(repository.findById(1L)).thenReturn(Optional.empty());

        service.enregistrerImport();

        ArgumentCaptor<DernierImport> captor = ArgumentCaptor.forClass(DernierImport.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getId()).isEqualTo(1L);
        assertThat(captor.getValue().getDateHeure()).isNotNull();
    }

    @Test
    void enregistrerImport_ligneDejaExistante_metAJourSaDateSansEnCreerUneAutre() {
        DernierImport existant = new DernierImport();
        existant.setId(1L);
        existant.setDateHeure(LocalDateTime.of(2020, 1, 1, 0, 0));
        when(repository.findById(1L)).thenReturn(Optional.of(existant));

        service.enregistrerImport();

        ArgumentCaptor<DernierImport> captor = ArgumentCaptor.forClass(DernierImport.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getId()).isEqualTo(1L);
        assertThat(captor.getValue().getDateHeure()).isAfter(LocalDateTime.of(2020, 1, 1, 0, 0));
    }
}
