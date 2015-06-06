package play.doc

import java.io.File
import java.util.{Collections, Arrays}
import org.pegdown._
import org.pegdown.plugins.{ToHtmlSerializerPlugin, PegDownPlugins}
import org.pegdown.ast._
import org.apache.commons.io.IOUtils
import play.doc.html.{toc, sidebar, nextLink}
import scala.collection.JavaConverters._

/**
 * A rendered page
 *
 * @param html The HTML for the page
 * @param sidebarHtml The HTML for the sidebar
 * @param path The path that the page was found at
 */
case class RenderedPage(html: String, sidebarHtml: Option[String], path: String)

/**
 * Config for play doc.
 *
 * @param resourcesPath The path to render links to resources at.
 * @param variables The variables.
 * @param linkParameters The link parameters.
 * @param nextText A translation of the word "Next" to render in next links.
 */
case class PlayDocConfig(resourcesPath: String, variables: Map[String, String], linkParameters: Map[String, String],
                         nextText: Option[String])

object PlayDoc {
  val LinkParameterPattern = """\{([^\}]+)\}""".r

  /**
   * Substitute all the link parameters in the given URL pattern.
   *
   * @param url The url to substitute
   * @param linkParameters The parameter map
   * @return The URL
   */
  def substituteLinks(url: String, linkParameters: Map[String, String]) = {
    LinkParameterPattern.replaceSomeIn(url, m => linkParameters.get(m.group(1)))
  }

  val VariablePattern = """%([^%]+)%""".r

  /**
   * Substitute all the variables in the given text.
   *
   * @param text The text to substitute
   * @param variables The variables
   * @return The text
   */
  def substituteVariables(text: String, variables: Map[String, String]) = {
    VariablePattern.replaceSomeIn(text, m => variables.get(m.group(1)))
  }

}

/**
 * Play documentation support
 *
 * @param markdownRepository Repository for finding markdown files
 * @param codeRepository Repository for finding code samples
 * @param pageIndex An optional page index. If None, will use the old approach of searching up the
 *                  heirarchy for sidebar pages, otherwise will use the page index to render the sidebar.
 * @param config The configuration.
 */
class PlayDoc(markdownRepository: FileRepository, codeRepository: FileRepository,
              val pageIndex: Option[PageIndex], config: PlayDocConfig) {

  import PlayDoc._

  val variables = config.variables.map {
    case (key, value) => key -> FastEncoder.encode(value)
  }
  val linkParameters = config.linkParameters.map {
    case (key, value) => key -> FastEncoder.encode(value)
  }

  /**
   * Render some markdown.
   */
  def render(markdown: String, relativePath: Option[File] = None): String = {
    withRenderer(relativePath.map(_.getPath), None)(_(markdown))
  }

  /**
   * Render a Play documentation page.
   *
   * @param pageName The page to render, without path or markdown extension.
   * @return If found a tuple of the rendered page and the rendered sidebar, if the sidebar was found.
   */
  def renderPage(pageName: String): Option[RenderedPage] = {
    pageIndex.fold {
      renderOldPage(pageName)
    } { index =>
      index.get(pageName).flatMap { page =>
        withRenderer(Some(page.path).filter(_.nonEmpty), page.nav.headOption) { renderer =>

          val pagePath = page.fullPath + ".md"
          val renderedPage = markdownRepository.loadFile(pagePath)(IOUtils.toString).map(renderer)

          renderedPage.map { html =>
            val withNext = page.next.zip(config.nextText).headOption.fold(html) {
              case (next, nextText) => html + nextLink(next, nextText).body
            }
            RenderedPage(withNext, Some(sidebar(page.nav).body), pagePath)
          }
        }
      }
    }
  }

  /**
   * Render all the pages of the documentation
   *
   * @param singlePage Whether the pages are being rendered to be formatted on a single page
   */
  def renderAllPages(singlePage: Boolean): List[(String, String)] = {
    pageIndex match {
      case Some(idx) =>
        def collectPagesInOrder(node: TocTree): List[String] = {
          node match {
            case TocPage(page, _) => List(page)
            case toc: Toc => toc.nodes.flatMap(n => collectPagesInOrder(n._2))
          }
        }
        val pages = collectPagesInOrder(idx.toc)
        pages.flatMap { pageName =>
          idx.get(pageName).flatMap { page =>
            withRenderer(Some(page.path).filter(_.nonEmpty), page.nav.headOption, singlePage = singlePage) { renderer =>
              val pagePath = page.fullPath + ".md"
              markdownRepository.loadFile(pagePath)(IOUtils.toString).map(renderer)
            }
          }.map { pageName -> _ }
        }
      case None =>
        throw new IllegalStateException("Can only render all pages if there's a page index")
    }
  }


  /**
   * Render a Play documentation page.
   *
   * @param page The page to render, without path or markdown extension.
   * @return If found a tuple of the rendered page and the rendered sidebar, if the sidebar was found.
   */
  private def renderOldPage(page: String): Option[RenderedPage] = {

    // Find the markdown file
    markdownRepository.findFileWithName(page + ".md").flatMap { pagePath =>

      val file = new File(pagePath)
      // Work out the relative path for the file
      val relativePath = Option(file.getParentFile).map(_.getPath)

      def render(path: String, headerIds: Boolean = true): Option[String] = {
        withRenderer(relativePath, None, headerIds = headerIds) { renderer =>
          markdownRepository.loadFile(path)(IOUtils.toString).map(renderer)
        }
      }

      // Recursively search for Sidebar
      def findSideBar(file: Option[File]): Option[String] = file match {
        case None => None
        case Some(parent) =>
          val sidebar = render(parent.getPath + "/_Sidebar.md", headerIds = false)
          sidebar.orElse(findSideBar(Option(parent.getParentFile)))
      }

      // Render both the markdown and the sidebar
      render(pagePath).map { markdown =>
        RenderedPage(markdown, findSideBar(Option(file.getParentFile)), pagePath)
      }
    }
  }

  private def withRenderer[T](relativePath: Option[String], toc: Option[Toc],
                              headerIds: Boolean = true,
                              singlePage: Boolean = false)(block: (String => String) => T): T = {

    // Link renderer
    val link: (String => (String, String)) = {
      case link if link.contains("|") =>
        val parts = link.split('|')
        val href = if (singlePage) "#" + parts.tail.head else parts.tail.head
        (href, parts.head)
      case image if image.endsWith(".png") =>
        val link = image match {
          case full if full.startsWith("http://") => full
          case absolute if absolute.startsWith("/") => config.resourcesPath + absolute
          case relative => config.resourcesPath + "/" + relativePath.map(_ + "/").getOrElse("") + relative
        }
        (link, """<img src="""" + link + """"/>""")
      case link =>
        if (singlePage) ("#" + link, link) else (link, link)
    }

    val links = new LinkRenderer {
      override def render(node: WikiLinkNode) = {
        val (href, text) = link(node.getText)
        new LinkRenderer.Rendering(href, text)
      }
      override def render(node: ExpLinkNode, text: String) = {
        val newNode = new ExpLinkNode(node.title, substituteLinks(node.url, linkParameters), node.getChildren.get(0))
        super.render(newNode, text)
      }
      override def render(node: AutoLinkNode) = {
        val url = substituteLinks(node.getText, linkParameters)
        new LinkRenderer.Rendering(url, url)
      }
    }

    val plugins = config.variables.foldLeft(
      PegDownPlugins.builder()
        .withPlugin(classOf[CodeReferenceParser])
        .withPlugin(classOf[TocParser])
    ) { (ps, variable) =>
      ps.withPlugin(classOf[VariableParser], variable._1)
    }.build()

    // Markdown parser
    val processor = new PegDownProcessor(Extensions.ALL, plugins)

    // ToHtmlSerializer's are stateful and so not reusable
    def htmlSerializer = new ToHtmlSerializer(links,
      Collections.singletonMap(VerbatimSerializer.DEFAULT,
        new VerbatimSerializerWrapper(PrettifyVerbatimSerializer)),
      Arrays.asList[ToHtmlSerializerPlugin](
        new CodeReferenceSerializer(relativePath.map(_ + "/").getOrElse("")),
        new VariableSerializer(variables),
        new TocSerializer(toc)
      )
    ){
      var headingsSeen = Map.empty[String, Int]
      def headingToAnchor(heading: String) = {
        val anchor = FastEncoder.encode(heading.replace(' ', '-'))
        headingsSeen.get(anchor).fold {
          headingsSeen += anchor -> 1
          anchor
        } { seen =>
          headingsSeen += anchor -> (seen + 1)
          anchor + seen
        }
      }

      override def visit(node: CodeNode) = {
        val text = substituteVariables(node.getText, variables)
        super.visit(new CodeNode(text))
      }

      override def visit(node: HeaderNode) = {
        // Put an id on header nodes
        printer.print("<h")
          .print(node.getLevel.toString)

        if (headerIds) {
          printer.print(" id=\"")

          import scala.collection.JavaConverters._
          def collectTextNodes(node: Node): Seq[String] = {
            node.getChildren.asScala.collect {
              case t: TextNode => Seq(t.getText)
              case other => collectTextNodes(other)
            }.flatten
          }
          val title = collectTextNodes(node).mkString

          printer.print(headingToAnchor(title))
            .print("\"")
        }

        printer.print(">")
        visitChildren(node)
        printer.print("</h").print(node.getLevel.toString).print(">")
      }

    }

    def render(markdown: String): String = {
      val astRoot = processor.parseMarkdown(markdown.toCharArray)
      htmlSerializer.toHtml(astRoot)
    }

    block(render)
  }

  // Directives to insert code, skip code and replace code
  private val Insert = """.*###insert: (.*?)(?:###.*)?""".r
  private val SkipN = """.*###skip:\s*(\d+).*""".r
  private val Skip = """.*###skip.*""".r
  private val ReplaceNext = """.*###replace: (.*?)(?:###.*)?""".r

  private class CodeReferenceSerializer(pagePath: String) extends ToHtmlSerializerPlugin {

    // Most files will be accessed multiple times from the same markdown file, no point in opening them many times
    // so memoize them.  This cache is only per file rendered, so does not need to be thread safe.
    val repo = Memoize[String, Option[Seq[String]]] { path =>
      codeRepository.loadFile(path) { is =>
        IOUtils.readLines(is).asScala.toSeq
      }
    }

    def visit(node: Node, visitor: Visitor, printer: Printer) = node match {
      case code: CodeReferenceNode => {

        // Label is after the #, or if no #, then is the link label
        val (source, label) = code.getSource.split("#", 2) match {
          case Array(source, label) => (source, label)
          case Array(source) => (source, code.getLabel)
        }

        // The file is either relative to current page or absolute, under the root
        val sourceFile = if (source.startsWith("/")) {
          repo(source.drop(1))
        } else {
          repo(pagePath + source)
        }

        val segment = if (label.nonEmpty) {
          val labelPattern = ("""\s*#\Q""" + label + """\E(\s|\z)""").r
          sourceFile.flatMap { sourceCode =>
            val notLabel = (s: String) => labelPattern.findFirstIn(s).isEmpty
            val segment = sourceCode dropWhile (notLabel) drop (1) takeWhile (notLabel)
            if (segment.isEmpty) {
              None
            } else {
              Some(segment)
            }
          }
        } else {
          sourceFile
        }

        segment.map { segment =>

          // Calculate the indent, which is equal to the smallest indent of any line, excluding lines that only consist
          // of space characters
          val indent = segment map { line =>
            if (!line.exists(_ != ' ')) None else Some(line.indexWhere(_ != ' '))
          } reduce ((i1, i2) => (i1, i2) match {
            case (None, None) => None
            case (i, None) => i
            case (None, i) => i
            case (Some(i1), Some(i2)) => Some(math.min(i1, i2))
          }) getOrElse (0)

          // Process directives in segment
          case class State(buffer: StringBuilder = new StringBuilder, skip: Option[Int] = None) {
            def dropIndentAndAppendLine(s: String): State = {
              buffer.append(s.drop(indent)).append("\n")
              this
            }
            def appendLine(s: String): State = {
              buffer.append(s).append("\n")
              this
            }
          }
          val compiledSegment = (segment.foldLeft(State()) { (state, line) =>
            state.skip match {
              case Some(n) if (n > 1) => state.copy(skip = Some(n - 1))
              case Some(n) => state.copy(skip = None)
              case None => line match {
                case Insert(code) => state.appendLine(code)
                case SkipN(n) => state.copy(skip = Some(n.toInt))
                case Skip() => state
                case ReplaceNext(code) => state.appendLine(code).copy(skip = Some(1))
                case _ => state.dropIndentAndAppendLine(line)
              }
            }
          }).buffer /* Drop last newline */ .dropRight(1).toString()

          // Guess the type of the file
          val fileType = source.split("\\.") match {
            case withExtension if (withExtension.length > 1) => Some(withExtension.last)
            case _ => None
          }

          // And visit it
          fileType.map(t => new VerbatimNode(compiledSegment, t)).getOrElse(new VerbatimNode(compiledSegment)).accept(visitor)

          true
        } getOrElse {
          printer.print("Unable to find label " + label + " in source file " + source)
          true
        }
      }
      case _ => false
    }
  }

  private class VariableSerializer(variables: Map[String, String]) extends ToHtmlSerializerPlugin {
    def visit(node: Node, visitor: Visitor, printer: Printer) = node match {
      case variable: VariableNode => {
        new TextNode(variables.get(variable.getName).getOrElse("Unknown variable: " + variable.getName)).accept(visitor)
        true
      }
      case _ => false
    }
  }

  private class VerbatimSerializerWrapper(wrapped: VerbatimSerializer) extends VerbatimSerializer {
    def serialize(node: VerbatimNode, printer: Printer) {
      val text = substituteVariables(node.getText, variables)
      wrapped.serialize(new VerbatimNode(text, node.getType), printer)
    }
  }

  private class TocSerializer(maybeToc: Option[Toc]) extends ToHtmlSerializerPlugin {
    def visit(node: Node, visitor: Visitor, printer: Printer) = node match {
      case tocNode: TocNode =>
        maybeToc.fold(false) { t =>
          printer.print(toc(t).body)
          true
        }
      case _ => false
    }
  }
}

private class Memoize[-T, +R](f: T => R) extends (T => R) {
  import scala.collection.mutable
  private[this] val vals = mutable.Map.empty[T, R]

  def apply(x: T): R = {
    if (vals.contains(x)) {
      vals(x)
    }
    else {
      val y = f(x)
      vals + ((x, y))
      y
    }
  }
}

private object Memoize {
  def apply[T, R](f: T => R) = new Memoize(f)
}

