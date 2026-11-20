package com.homebank.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Component;

/**
 * Demarrage rapide (voir HomeBank.vbs) : avec des depots charges a la demande,
 * chacun est prepare ici sur son propre fil pendant que le serveur web demarre.
 * L'analyse de leurs requetes, de loin l'etape la plus longue du demarrage, se
 * repartit ainsi entre les coeurs du processeur au lieu de s'enchainer. Le
 * comptage ne sert qu'a declencher cette preparation.
 *
 * Seuls les NOMS des depots sont lus sur le fil principal : demander les depots
 * eux-memes (getBeansOfType) les construisait tous, l'un apres l'autre, avant
 * meme de lancer les fils (vu dans un vidage des fils : ~0,6 s d'analyse de
 * requetes sur le fil principal, port du serveur ouvert d'autant plus tard).
 */
@Component
@ConditionalOnProperty(name = "spring.data.jpa.repositories.bootstrap-mode", havingValue = "lazy")
public class PreparationDepots implements SmartInitializingSingleton {

    private static final Logger log = LoggerFactory.getLogger(PreparationDepots.class);

    private final ListableBeanFactory beanFactory;

    public PreparationDepots(ListableBeanFactory beanFactory) {
        this.beanFactory = beanFactory;
    }

    @Override
    public void afterSingletonsInstantiated() {
        for (String name : beanFactory.getBeanNamesForType(JpaRepository.class, false, false)) {
            Thread thread = new Thread(() -> preparer(name), "preparation-" + name);
            thread.setDaemon(true);
            thread.start();
        }
    }

    /** Un echec ne bloque rien : le depot sera prepare, ou signalera son erreur, a sa premiere utilisation. */
    private void preparer(String name) {
        try {
            beanFactory.getBean(name, JpaRepository.class).count();
        } catch (RuntimeException e) {
            log.warn("Preparation du depot {} en echec : {}", name, e.getMessage());
        }
    }
}
