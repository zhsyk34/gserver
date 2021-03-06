package com.katesoft.gserver.domain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.apache.commons.lang3.builder.EqualsBuilder;
import org.junit.Assert;
import org.junit.Test;
import org.springframework.dao.DuplicateKeyException;

import com.google.common.collect.ImmutableSet;
import com.katesoft.gserver.domain.Entities.BetLimits;
import com.katesoft.gserver.domain.Entities.Coin;
import com.katesoft.gserver.domain.Entities.Coins;
import com.katesoft.gserver.games.RouletteGame;

public class DomainRepositoryTest extends AbstractDomainTest {
    @Test
    public void users() {
        UserAccountBO acc = new UserAccountBO();
        acc.setFirstname( "gserver_firstname" );
        acc.setLastname( "gserver_lastname" );
        acc.setEmail( "gserver@gmail.com" );
        acc.setProvider( "facebook" );
        acc.setProviderUserId( String.valueOf( System.currentTimeMillis() ) );
        acc.setPassword( "gserver_password" );
        acc.setUsername( "gserver_username" );

        repo.saveUserAccount( acc );
        UserAccountBO copy = repo.findUserAccount( acc.getPrimaryKey() ).get();
        assertTrue( EqualsBuilder.reflectionEquals( acc, copy, false ) );

        try {
            repo.saveUserAccount( acc );
            Assert.fail();
        }
        catch ( DuplicateKeyException e ) {}
        repo.deleteUserAccount( acc );
    }
    @Test
    public void games() {
        GameBO bo1 = new GameBO( "amrl", "American Roulette", RouletteGame.class.getName() );
        GameBO bo2 = new GameBO( "eurl", "Europeane Roulette", RouletteGame.class.getName() );

        repo.saveGame( bo1 );
        repo.saveGame( bo2 );
        GameBO clone1 = repo.findGame( bo1.getPrimaryKey() ).get();
        GameBO clone2 = repo.findGame( bo2.getPrimaryKey() ).get();
        assertTrue( EqualsBuilder.reflectionEquals( bo1, clone1, false ) );
        assertTrue( EqualsBuilder.reflectionEquals( bo2, clone2, false ) );
        assertEquals( repo.findAllGames().size(), 2 );
    }
    @Test
    public void playerSessions() {
        GameBO game = new GameBO( "amrl", "American Roulette", RouletteGame.class.getName() );
        repo.saveGame( game );

        BetLimits blimits = BetLimits.newBuilder().setMaxBet( Short.MAX_VALUE ).setMinBet( 1 ).build();
        Coins coins = Coins.newBuilder().addAllCoins( ImmutableSet.copyOf( Coin.values() ) ).build();

        PlayerSessionBO playerSession = new PlayerSessionBO( "session-x", "player-x", "connection-x", blimits, coins, game, "Mozilla 3.0" );
        repo.savePlayerSession( playerSession );
        PlayerSessionBO clone = repo.findPlayerSession( playerSession.getPrimaryKey() ).get();
        assertTrue( EqualsBuilder.reflectionEquals( playerSession, clone, false ) );
    }
}
