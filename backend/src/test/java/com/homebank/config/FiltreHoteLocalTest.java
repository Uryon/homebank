package com.homebank.config;

import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

class FiltreHoteLocalTest {

    private static MockFilterChain traiter(String adresseEcoute, String hote, MockHttpServletResponse reponse)
            throws ServletException, IOException {
        MockHttpServletRequest requete = new MockHttpServletRequest("GET", "/api/accounts");
        requete.setServerName(hote);
        MockFilterChain chaine = new MockFilterChain();
        new FiltreHoteLocal(adresseEcoute).doFilter(requete, reponse, chaine);
        return chaine;
    }

    @Test
    void laissePasserLesNomsLocaux() throws Exception {
        for (String hote : new String[]{"127.0.0.1", "localhost", "LOCALHOST", "[::1]"}) {
            MockHttpServletResponse reponse = new MockHttpServletResponse();

            assertThat(traiter("127.0.0.1", hote, reponse).getRequest()).isNotNull();
            assertThat(reponse.getStatus()).isEqualTo(200);
        }
    }

    @Test
    void refuseUnNomDeDomaineQuiPointeraitVersLaMachine() throws Exception {
        MockHttpServletResponse reponse = new MockHttpServletResponse();

        MockFilterChain chaine = traiter("127.0.0.1", "site-malveillant.example", reponse);

        assertThat(chaine.getRequest()).isNull();
        assertThat(reponse.getStatus()).isEqualTo(403);
    }

    @Test
    void resteInactifQuandLeServeurEstVolontairementExposeAuReseau() throws Exception {
        MockHttpServletResponse reponse = new MockHttpServletResponse();

        assertThat(traiter("0.0.0.0", "nas.maison", reponse).getRequest()).isNotNull();
        assertThat(reponse.getStatus()).isEqualTo(200);
    }
}
