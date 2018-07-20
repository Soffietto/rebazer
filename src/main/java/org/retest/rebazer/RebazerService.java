package org.retest.rebazer;

import static org.retest.rebazer.config.RebazerConfig.POLL_INTERVAL_DEFAULT;
import static org.retest.rebazer.config.RebazerConfig.POLL_INTERVAL_KEY;

import org.retest.rebazer.config.RebazerConfig;
import org.retest.rebazer.config.RebazerConfig.RepositoryConfig;
import org.retest.rebazer.config.RebazerConfig.RepositoryHost;
import org.retest.rebazer.config.RebazerConfig.RepositoryTeam;
import org.retest.rebazer.connector.RepositoryConnector;
import org.retest.rebazer.domain.PullRequest;
import org.retest.rebazer.service.PullRequestLastUpdateStore;
import org.retest.rebazer.service.RebaseService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor( onConstructor = @__( @Autowired ) )
public class RebazerService {

	private final RebaseService rebaseService;
	private final RebazerConfig rebazerConfig;
	private final PullRequestLastUpdateStore pullRequestLastUpdateStore;

	private final RestTemplateBuilder builder;

	@Scheduled( fixedDelayString = "${" + POLL_INTERVAL_KEY + ":" + POLL_INTERVAL_DEFAULT + "}000" )
	public void pollToHandleAllPullRequests() {
		rebazerConfig.getHosts().forEach( repoHost -> {
			repoHost.getTeams().forEach( repoTeam -> {
				repoTeam.getRepos().forEach( repoConfig -> {
					handleRepo( repoHost, repoTeam, repoConfig );
				} );
			} );
		} );
	}

	private void handleRepo( final RepositoryHost repoHost, final RepositoryTeam repoTeam,
			final RepositoryConfig repoConfig ) {
		log.debug( "Processing {}.", repoConfig );
		final RepositoryConnector repoConnector = repoHost.getType().getRepository( repoTeam, repoConfig, builder );
		for ( final PullRequest pullRequest : repoConnector.getAllPullRequests( repoConfig ) ) {
			handlePullRequest( repoConnector, repoConfig, pullRequest );
		}
		log.debug( "Processing done for {}.", repoConfig );
	}

	public void handlePullRequest( final RepositoryConnector repoConnector, final RepositoryConfig repoConfig,
			final PullRequest pullRequest ) {
		log.debug( "Processing {}.", pullRequest );

		if ( pullRequestLastUpdateStore.isHandled( repoConfig, pullRequest ) ) {
			log.info( "{} is unchanged since last run (last change: {}).", pullRequest,
					pullRequestLastUpdateStore.getLastDate( repoConfig, pullRequest ) );

		} else if ( !repoConnector.greenBuildExists( pullRequest ) ) {
			log.info( "Waiting for green build of {}.", pullRequest );
			pullRequestLastUpdateStore.setHandled( repoConfig, pullRequest );

		} else if ( repoConnector.rebaseNeeded( pullRequest ) ) {
			if ( !rebaseService.rebase( repoConfig, pullRequest ) ) {
				repoConnector.addComment( pullRequest );
			}
			// we need to update the "lastUpdate" of a PullRequest to counteract if addComment is called
			pullRequestLastUpdateStore.setHandled( repoConfig, repoConnector.getLatestUpdate( pullRequest ) );

		} else if ( !repoConnector.isApproved( pullRequest ) ) {
			log.info( "Waiting for approval of {}.", pullRequest );
			pullRequestLastUpdateStore.setHandled( repoConfig, pullRequest );

		} else {
			log.info( "Merging pull request " + pullRequest );
			repoConnector.merge( pullRequest );
			pullRequestLastUpdateStore.resetAllInThisRepo( repoConfig );
		}
	}

}
