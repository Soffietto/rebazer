package org.retest.rebazer.connector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.retest.rebazer.config.RebazerConfig.RepositoryConfig;
import org.retest.rebazer.config.RebazerConfig.Team;
import org.retest.rebazer.domain.PullRequest;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.web.client.RestTemplate;

import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class GithubConnector implements RepositoryConnector {

	private final static String baseUrl = "https://api.github.com/";

	private final Team team;
	RepositoryConfig repo;

	private final RestTemplate template;

	public GithubConnector( final Team team, final RepositoryConfig repo, final RestTemplateBuilder builder ) {
		this.team = team;
		this.repo = repo;

		template = builder.basicAuthorization( team.getUser(), team.getPass() ).rootUri( baseUrl ).build();
	}

	@Override
	public PullRequest getLatestUpdate( final PullRequest pullRequest ) {
		final DocumentContext jp = jsonPathForPath( pullRequest.getUrl() );
		final PullRequest updatedPullRequest = PullRequest.builder() //
				.id( pullRequest.getId() ) //
				.repo( pullRequest.getRepo() ) //
				.source( pullRequest.getSource() ) //
				.destination( pullRequest.getDestination() ) //
				.url( pullRequest.getUrl() ) //
				.lastUpdate( jp.read( "$.updated_at" ) ) //
				.build();
		return updatedPullRequest;
	}

	@Override
	public boolean isApproved( final PullRequest pullRequest ) {
		final DocumentContext jp = jsonPathForPath( pullRequest.getUrl() + "/reviews" );
		final List<String> states = jp.read( "$..state" );
		boolean approved = false;
		for ( final String state : states ) {
			if ( state.equals( "APPROVED" ) ) {
				approved = true;
			} else {
				approved = false;
			}
		}
		return approved;
	}

	@Override
	public boolean rebaseNeeded( final PullRequest pullRequest ) {
		return !getLastCommonCommitId( pullRequest ).equals( getHeadOfBranch( pullRequest ) );
	}

	String getHeadOfBranch( final PullRequest pullRequest ) {
		final String url = "/repos/" + team.getName() + "/" + pullRequest.getRepo() + "/";
		return jsonPathForPath( url + "git/refs/heads/" + pullRequest.getDestination() ).read( "$.object.sha" );
	}

	String getLastCommonCommitId( final PullRequest pullRequest ) {
		final DocumentContext jp = jsonPathForPath( pullRequest.getUrl() + "/commits" );

		final List<String> commitIds = jp.read( "$..sha" );
		final List<String> parentIds = jp.read( "$..parents..sha" );

		return parentIds.stream().filter( parent -> commitIds.contains( parent ) ).findFirst()
				.orElseThrow( IllegalStateException::new );
	}

	@Override
	public void merge( final PullRequest pullRequest ) {
		log.warn( "Merging pull request {}", pullRequest );
		final String message = String.format( "Merged in %s (pull request #%d) by ReBaZer", pullRequest.getSource(),
				pullRequest.getId() );
		final Map<String, String> request = new HashMap<>();
		request.put( "commit_title", message );
		request.put( "merge_method", "merge" );

		template.put( pullRequest.getUrl() + "/merge", request, Object.class );
	}

	@Override
	public boolean greenBuildExists( final PullRequest pullRequest ) {
		final String urlPath = "/repos/" + team.getName() + "/" + pullRequest.getRepo() + "/commits/"
				+ pullRequest.getSource() + "/status";
		final DocumentContext jp = jsonPathForPath( urlPath );
		return jp.<List<String>> read( "$.statuses[*].state" ).stream().anyMatch( s -> s.equals( "success" ) );
	}

	@Override
	public List<PullRequest> getAllPullRequests( final RepositoryConfig repo ) {
		final String urlPath = "/repos/" + team.getName() + "/" + repo.getName() + "/pulls";
		final DocumentContext jp = jsonPathForPath( urlPath );
		return parsePullRequestsJson( repo, urlPath, jp );
	}

	public static List<PullRequest> parsePullRequestsJson( final RepositoryConfig repo, final String urlPath,
			final DocumentContext jp ) {
		final List<Integer> pullRequestAmount = jp.read( "$..number" );
		final int numPullRequests = pullRequestAmount.size();
		final List<PullRequest> results = new ArrayList<>( numPullRequests );
		for ( int i = 0; i < numPullRequests; i++ ) {
			final int id = pullRequestAmount.get( i );
			final String source = jp.read( "$.[" + i + "].head.ref" );
			final String destination = jp.read( "$.[" + i + "].base.ref" );
			final String lastUpdate = jp.read( "$.[" + i + "].updated_at" );
			results.add( PullRequest.builder() //
					.id( id ) //
					.repo( repo.getName() ) //
					.source( source ) //
					.destination( destination ) //
					.url( urlPath + "/" + id ) //
					.lastUpdate( lastUpdate ) //
					.build() ); //
		}
		return results;
	}

	private DocumentContext jsonPathForPath( final String urlPath ) {
		final String json = template.getForObject( urlPath, String.class );
		return JsonPath.parse( json );
	}

	@Override
	public void addComment( final PullRequest pullRequest ) {
		final Map<String, String> request = new HashMap<>();
		request.put( "body", "This pull request needs some manual love ..." );
		template.postForObject( "/repos/" + team.getName() + "/" + pullRequest.getRepo() + "/issues/"
				+ pullRequest.getId() + "/comments", request, String.class );
	}

}
