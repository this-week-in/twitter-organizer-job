package com.joshlong.twitter.organizer

import org.apache.commons.logging.LogFactory
import org.springframework.batch.core.ExitStatus
import org.springframework.batch.core.Job
import org.springframework.batch.core.Step
import org.springframework.batch.core.configuration.annotation.EnableBatchProcessing
import org.springframework.batch.core.configuration.annotation.JobBuilderFactory
import org.springframework.batch.core.configuration.annotation.StepBuilderFactory
import org.springframework.batch.core.launch.support.RunIdIncrementer
import org.springframework.batch.item.ItemReader
import org.springframework.batch.item.ItemWriter
import org.springframework.batch.item.database.builder.JdbcBatchItemWriterBuilder
import org.springframework.batch.item.support.IteratorItemReader
import org.springframework.batch.repeat.RepeatStatus
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.autoconfigure.batch.JobExecutionEvent
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.runApplication
import org.springframework.context.ApplicationListener
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.support.GenericApplicationContext
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.social.twitter.api.Twitter
import org.springframework.social.twitter.api.impl.TwitterTemplate
import org.springframework.stereotype.Component
import java.sql.ResultSet
import javax.sql.DataSource


fun main(args: Array<String>) {
	runApplication<TwitterListMakerApplication>(*args)
}
/*

@ConfigurationProperties(prefix = "organizer")
class TwitterOrganizerProperties(val clientKey: String? = null,
                                 val clientKeySecret: String? = null,
                                 val accessToken: String? = null,
                                 val accessTokenSecret: String? = null)
*/


/**
 * TODO
 * 1. run thru all the followers on twitter for my account
 * 2. store their information into a DB
 * 3. figure out how to sort them into different categories. maybe use neo4j to identify clusters?
 *
 * Twitter limits us to 15 calls per 15 minutes, so this will be a job that periodically runs through as many records
 * in the DB as possible, enriches their information and then marks those rows as processed. The next time the job
 * spins up it'll attempt to process the unfinished rows. It'll keep going until every follower's info (in
 * a couple of days) has been enriched. Then we can figure out what to do from there.
 *
 * @author <a href="mailto:josh@joshlong.com">Josh Long</a>
 */
@EnableConfigurationProperties(TwitterOrganizerProperties::class)
@SpringBootApplication
@EnableBatchProcessing
class TwitterListMakerApplication {

	private val log = LogFactory.getLog(javaClass)

	@Bean
	fun jdbcTemplate(ds: DataSource) = JdbcTemplate(ds)

	/**
	 * We want to shutdown the node once we've done whatever we need to.
	 */
	@Bean
	fun listener(ac: GenericApplicationContext) = ApplicationListener<JobExecutionEvent> {
		log.debug(it.jobExecution.exitStatus)
		val done = it.jobExecution.exitStatus == ExitStatus.COMPLETED
		if (done) {
			ac.close()
		}
	}

	@Configuration
	class IsProfileIdsEmptyStepConfig(
			private val jdbcTemplate: JdbcTemplate,
			private val stepBuilderFactory: StepBuilderFactory) {

		companion object {
			const val IMPORT_PROFILE_IDS = "IMPORT_PROFILE_IDS"
		}

		@Bean
		fun step(): Step =
				stepBuilderFactory
						.get("is-profile-ids-empty")
						.tasklet({ contribution, _ ->


							//							val import = this.jdbcTemplate.queryForObject("SELECT COUNT(*) FROM PROFILE_IDS", Long::class.java) == 0L
//							println("import? $import")

							val import = false
							contribution.exitStatus =
									if (import)
										ExitStatus(IsProfileIdsEmptyStepConfig.IMPORT_PROFILE_IDS)
									else
										ExitStatus.COMPLETED
							/*		if (import)
										ExitStatus(IsProfileIdsEmptyStepConfig.IMPORT_PROFILE_IDS)
									else
										ExitStatus.COMPLETED*/

							RepeatStatus.FINISHED

						})
						.build()
	}

	/**
	 * this step manages the first step: import en-masse all the follower IDs from Twitter.
	 * This is an action we can take in one API call (I think).
	 */
	@Configuration
	class ImportProfileIdsStepConfig(val twitter: Twitter,
	                                 val ds: DataSource,
	                                 val sbf: StepBuilderFactory) {

		@Bean
		fun reader(): ItemReader<Long> =
				IteratorItemReader<Long>(twitter.friendOperations().friendIds.iterator())

		@Bean
		fun writer(): ItemWriter<Long> =
				JdbcBatchItemWriterBuilder<Long>()
						.sql(" replace into PROFILE_IDS(ID) values(?) ") // MySQL-nese for 'upsert'
						.dataSource(ds)
						.itemPreparedStatementSetter { id, ps -> ps.setLong(1, id) }
						.build()

		@Bean
		fun step(): Step =
				sbf.get("fetch-all-twitter-ids")
						.chunk<Long, Long>(10000)
						.reader(this.reader())
						.writer(this.writer())
						.build()
	}

	@Bean
	fun job(jbf: JobBuilderFactory,
	        sbf: StepBuilderFactory,
	        isProfileIdsEmptyStepConfigConfig: IsProfileIdsEmptyStepConfig,
	        importProfileIdsStepConfigConfig: ImportProfileIdsStepConfig): Job {

		val taskletStep = sbf
				.get("tasklet")
				.tasklet({ contribution, chunkContext ->
					println("finishing...")
					RepeatStatus.FINISHED
				})
				.build()

		val importProfileIdsStep = importProfileIdsStepConfigConfig.step()
		val isProfileIdsEmpty = isProfileIdsEmptyStepConfigConfig.step()


		val finishExitStatus = ExitStatus.COMPLETED.exitCode

		val importStep = sbf
				.get("importStep")
				.tasklet { c, e ->
					println("importStep!")
					RepeatStatus.FINISHED
				}
				.build()

		return jbf
				.get("twitter-organizer")
				.incrementer(RunIdIncrementer())
				.flow(isProfileIdsEmpty).on(IsProfileIdsEmptyStepConfig.IMPORT_PROFILE_IDS).to(importStep)
				.from(isProfileIdsEmpty).on("*").to(taskletStep)
				.from(importStep).on("*").to(taskletStep)
				.build()
				.build()

	}


	@Bean
	fun twitter(props: TwitterOrganizerProperties) = TwitterTemplate(props.clientKey, props.clientKeySecret,
			props.accessToken, props.accessTokenSecret)
}

class Profile(var id: Long,
              var following: Boolean? = null,
              var verified: Boolean? = null,
              var name: String? = null,
              var description: String? = null,
              var screenName: String? = null)

@Component
class FollowingRowMapper : RowMapper<Profile> {

	override fun mapRow(rs: ResultSet, indx: Int) =
			Profile(
					rs.getLong("id"),
					rs.getBoolean("following"),
					rs.getBoolean("verified"),
					rs.getString("name"),
					rs.getString("description"),
					rs.getString("screen_name"))
}

/*


@Configuration
class FollowingStepConfiguration(val jbf: JobBuilderFactory, val sbf: StepBuilderFactory, val ds: DataSource) {

	@Bean
	fun followingSqlItemReader() = JdbcCursorItemReaderBuilder<Following>()
			.sql("select * from following")
			.rowMapper(FollowingRowMapper())
			.dataSource(ds)
			.name("followingSqlItemReader")
			.build()

	@Bean
	fun enrichingProcessor(twitter: Twitter) = ItemProcessor<Following, Following> { following ->
		val userProfile = twitter.userOperations().getUserProfile(following.id)
		following.description = userProfile.description
		following.following = userProfile.isFollowing
		following.screenName = userProfile.screenName
		following.verified = userProfile.isVerified
		following.name = userProfile.name
		return@ItemProcessor following
	}

	@Bean
	fun followingSqlItemWriter() = null
}

@Configuration
class TwitterProfileEnrichmentJobConfiguration*/
/*

@Component
class Initializer(val twitter: Twitter,
                  val tt: TransactionTemplate,
                  val jdbc: JdbcTemplate) : ApplicationRunner {


    val log = LoggerFactory.getLogger(this::class.java)
    val username = "@starbuxman"


    override fun run(args: ApplicationArguments?) {


        twitter.friendOperations().friendIds.forEach({ id ->
            val followingSql = """
                        INSERT INTO following(
                            id,
                            following,
                            verified,
                            name,
                            description,
                            screen_name
                        )
                        VALUES (?, ?, ?, ?, ?, ?)
                    """
            jdbc.update(followingSql, id, false, false, null, null, null)
        })

    }
}
*/


/*  private fun old() {
        val maxCursorStateIdSql = """
            SELECT cs.cursor_state FROM cursor_state cs WHERE cs.id IN (SELECT max(cs1.id) FROM cursor_state cs1)
        """

        val maxCursorState: List<Long> = jdbc.query(maxCursorStateIdSql, {
            rs, i ->
            rs.getLong("cursor_state")
        })
        log.info("max cursor state: ${maxCursorState}")

        var friends: CursoredList<TwitterProfile>? = null
        if (maxCursorState.size == 1) {
            friends = twitter.friendOperations().getFriendsInCursor(username, maxCursorState.single())
        } else {
            friends = twitter.friendOperations().getFriends(username)
        }
        do {

            tt.execute {
                friends!!.forEach({
                    val followingSql = """
                        INSERT INTO following(
                            id,
                            following,
                            verified,
                            name,
                            description,
                            screen_name
                        )
                        VALUES (?, ?, ?, ?, ?, ?)
                    """
                    jdbc.update(followingSql, it.id, it.isFollowing, it.isVerified, it.name, it.description, it.screenName)
                    log.info("""
                        id: ${it.id}
                        isFollowing: ${it.isFollowing}
                        isVerified: ${it.isVerified}
                        name: ${it.name}
                        description: ${it.description}
                        screen-name: ${it.screenName}
                    """.split(System.lineSeparator())
                            .map { it.trim() }
                            .joinToString(System.lineSeparator())
                            .trim())
                })
                val nextCursor = friends!!.nextCursor
                friends = twitter.friendOperations().getFriendsInCursor(username, nextCursor)
                val stateSql = """
                  INSERT INTO cursor_state(cursor_state) VALUES (?)
                """
                jdbc.update(stateSql, nextCursor)
            }

        } while (friends!!.hasNext())
    }*/