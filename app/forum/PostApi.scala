package lila
package forum

import user.User
import http.Context
import mod.ModlogApi

import scalaz.effects._
import com.github.ornicar.paginator._
import scala.math.ceil

final class PostApi(
    env: ForumEnv,
    modLog: ModlogApi,
    maxPerPage: Int) {

  def create(categSlug: String, slug: String, page: Int): IO[Option[(Categ, Topic, Paginator[Post])]] =
    for {
      categOption ← env.categRepo bySlug categSlug
      topicOption ← env.topicRepo.byTree(categSlug, slug)
    } yield categOption |@| topicOption apply {
      case (categ, topic) ⇒ (categ, topic, env.postApi.paginator(topic, page))
    }

  def makePost(
    categ: Categ,
    topic: Topic,
    data: DataForm.PostData)(implicit ctx: Context): IO[Post] = for {
    number ← lastNumberOf(topic)
    post = Post(
      topicId = topic.id,
      author = data.author,
      userId = ctx.me map (_.id),
      ip = ctx.isAnon option ctx.req.remoteAddress,
      text = data.text,
      number = number + 1,
      categId = categ.id)
    _ ← env.postRepo saveIO post
    // denormalize topic
    _ ← env.topicRepo saveIO topic.copy(
      nbPosts = topic.nbPosts + 1,
      lastPostId = post.id,
      updatedAt = post.createdAt)
    // denormalize categ
    _ ← env.categRepo saveIO categ.copy(
      nbPosts = categ.nbPosts + 1,
      lastPostId = post.id)
    _ ← env.recent.invalidate
  } yield post

  def get(postId: String): IO[Option[(Topic, Post)]] = for {
    postOption ← env.postRepo byId postId
    topicOption ← postOption.fold(
      post ⇒ env.topicRepo byId post.topicId,
      io(none[Topic])
    )
  } yield (topicOption |@| postOption).tupled

  def views(posts: List[Post]): IO[List[PostView]] = for {
    topics ← env.topicRepo byIds posts.map(_.topicId).distinct
    categs ← env.categRepo byIds topics.map(_.categId).distinct
  } yield (for {
    post ← posts
  } yield for {
    topic ← topics find (_.id == post.topicId)
    categ ← categs find (_.slug == topic.categId)
  } yield PostView(post, topic, categ, lastPageOf(topic))
  ).flatten

  def view(post: Post): IO[Option[PostView]] = views(List(post)) map (_.headOption)

  def liteViews(posts: List[Post]): IO[List[PostLiteView]] = for {
    topics ← env.topicRepo byIds posts.map(_.topicId).distinct
  } yield (for {
    post ← posts
  } yield for {
    topic ← topics find (_.id == post.topicId)
  } yield PostLiteView(post, topic, lastPageOf(topic))
  ).flatten

  def lastNumberOf(topic: Topic): IO[Int] =
    env.postRepo lastByTopics List(topic) map (_.number)

  def lastPageOf(topic: Topic) =
    ceil(topic.nbPosts / maxPerPage.toFloat).toInt

  def paginator(topic: Topic, page: Int): Paginator[Post] =
    Paginator(
      SalatAdapter(
        dao = env.postRepo,
        query = env.postRepo byTopicQuery topic,
        sort = env.postRepo.sortQuery),
      currentPage = page,
      maxPerPage = maxPerPage
    ) | paginator(topic, 1)

  def delete(postId: String, mod: User): IO[Unit] = for {
    postOption ← env.postRepo byId postId
    viewOption ← ~postOption.map(view)
    _ ← ~viewOption.map(view ⇒ for {
      deleteTopic ← env.postRepo.isFirstPost(view.topic.id, view.post.id)
      _ ← deleteTopic.fold(
        env.topicApi.delete(view.categ, view.topic),
        for {
          _ ← env.postRepo removeIO view.post
          _ ← env.topicApi denormalize view.topic
          _ ← env.categApi denormalize view.categ
          _ ← env.recent.invalidate
        } yield ()
      )
      post = view.post
      _ ← modLog.deletePost(mod, post.userId, post.author, post.ip,
        text = "%s / %s / %s".format(view.categ.name, view.topic.name, post.text))
    } yield ())
  } yield ()
}
