(ns babashka.neil
  {:no-doc true}
  (:require
   [babashka.cli :as cli]
   [babashka.fs :as fs]
   [babashka.neil.curl :refer [curl-get-json url-encode]]
   [babashka.neil.git :as git]
   [babashka.neil.new :as new]
   [babashka.neil.project :as proj]
   [babashka.neil.rewrite :as rw]
   [babashka.neil.test :as neil-test]
   [babashka.neil.version :as neil-version]
   [borkdude.rewrite-edn :as r]
   [clojure.edn :as edn]
   [clojure.string :as str]))

(def spec {:lib {:desc "Fully qualified library name."}
           :version {:desc "Optional. When not provided, picks newest version from Clojars or Maven Central."}
           :sha {:desc "When provided, assumes lib refers to Github repo."}
           :latest-sha {:desc "When provided, assumes lib refers to Github repo and then picks latest SHA from it."}
           :deps/root {:desc "Sets deps/root to give value."}
           :as {:desc "Use as dependency name in deps.edn"
                :coerce :symbol}
           :alias {:ref "<alias>"
                   :desc "Add to alias <alias>."
                   :coerce :keyword}
           :deps-file {:ref "<file>"
                       :desc "Add to <file> instead of deps.edn."
                       :default "deps.edn"}
           :limit {:coerce :long}})

(def windows? (fs/windows?))

(def bb? (System/getProperty "babashka.version"))

(defn- get-clojars-artifact [qlib]
  (curl-get-json
   (format "https://clojars.org/api/artifacts/%s"
           qlib)))

(defn latest-clojars-version [qlib]
  (get (get-clojars-artifact qlib) :latest_release))

(defn clojars-versions [qlib {:keys [limit] :or {limit 10}}]
  (let [body (get-clojars-artifact qlib)]
    (->> body
         :recent_versions
         (map :version)
         (take limit))))

(defn- search-mvn [qlib limit]
  (:response
   (curl-get-json
    (format "https://search.maven.org/solrsearch/select?q=g:%s+AND+a:%s&rows=%s"
            (namespace qlib)
            (name qlib)
            (str limit)))))

(defn latest-mvn-version [qlib]
  (-> (search-mvn qlib 1)
      :docs
      first
      :latestVersion))

(defn mvn-versions [qlib {:keys [limit] :or {limit 10}}]
  (let [payload (search-mvn qlib limit)]
    (->> payload
         :docs
         (map :v))))

(def deps-template
  (str/triml "
{:deps {}
 :aliases {}}
"))

(def bb-template
  (str/triml "
{:deps {}
 :tasks
 {
 }}
"))

(defn ensure-deps-file [opts]
  (let [target (:deps-file opts)]
    (when-not (fs/exists? target)
      (spit target (if (= "bb.edn" target)
                     bb-template
                     deps-template)))))

(defn edn-string [opts] (slurp (:deps-file opts)))

(defn edn-nodes [edn-string] (r/parse-string edn-string))

(def cognitect-test-runner-alias
  "
{:extra-paths [\"test\"]
 :extra-deps {io.github.cognitect-labs/test-runner
               {:git/tag \"v0.5.0\" :git/sha \"b3fd0d2\"}}
 :main-opts [\"-m\" \"cognitect.test-runner\"]
 :exec-fn cognitect.test-runner.api/test}")

(defn add-alias [opts alias-kw alias-contents]
  (ensure-deps-file opts)
  (let [edn-string (edn-string opts)
        edn-nodes (edn-nodes edn-string)
        edn (edn/read-string edn-string)
        alias (or (:alias opts)
                  alias-kw)
        existing-aliases (get-in edn [:aliases])
        alias-node (r/parse-string
                    (str (when (seq existing-aliases) "\n ")
                         alias
                         " ;; added by neil"))]
    (if-not (get existing-aliases alias)
      (let [s (-> (if-not (seq existing-aliases)
                                        ; If there are no existing aliases, we assoc an empty map
                                        ; before updating to prevent borkdude.rewrite-edn/update
                                        ; from removing the newline preceding the :aliases key.
                    (r/assoc edn-nodes :aliases {})
                    edn-nodes)
                  (r/update :aliases
                            (fn [aliases]
                              (let [s (rw/indent alias-contents 1)
                                    alias-nodes (r/parse-string s)
                                    aliases' (r/assoc aliases alias-node alias-nodes)]
                                (if-not (seq existing-aliases)
                                        ; If there are no existing aliases, add an
                                        ; explicit newline after the :aliases key.
                                  (r/parse-string (str "\n" (rw/indent (str aliases') 1)))
                                  aliases'))))

                  str)
            s (rw/clean-trailing-whitespace s)
            s (str s "\n")]
        (spit (:deps-file opts) s))
      (do (println (format "[neil] Project deps.edn already contains alias %s" (str alias ".")))
          ::update))))

(declare print-help)

(defn add-cognitect-test-runner [{:keys [opts] :as cmd}]
  (if (:help opts)
    (print-help cmd)
    (do (add-alias opts :test cognitect-test-runner-alias)

        (when-let [pn (proj/project-name opts)]
          (let [test-ns (symbol (str (str/replace pn "/" ".") "-test"))
                test-path (-> (str test-ns)
                              (str/replace "-" "_")
                              (str/replace "." fs/file-separator)
                              (str ".clj"))
                test-path (fs/file "test" test-path)]
            (when (or (not (fs/exists? "test"))
                      (zero? (count (fs/list-dir "test"))))
              (fs/create-dirs (fs/parent test-path))
              (spit test-path
                    (format "(ns %s
  (:require [clojure.test :as t :refer [deftest is testing]]))

(deftest %s-test
  (testing \"TODO: fix\"
    (is (= :foo :bar))))
" test-ns (name pn)))))))))

(def kaocha-alias
  "
{:extra-deps {lambdaisland/kaocha {:mvn/version \"1.0.887\"}}}")

(defn add-kaocha [{:keys [opts] :as cmd}]
  (if (:help opts)
    (print-help cmd)
    (add-alias opts :kaocha kaocha-alias)))

(defn nrepl-alias []
  (format "
{:extra-deps {nrepl/nrepl {:mvn/version \"%s\"}}
 :main-opts [\"-m\" \"nrepl.cmdline\" \"--interactive\" \"--color\"]}"
          (latest-clojars-version 'nrepl/nrepl)))

(defn add-nrepl [{:keys [opts] :as cmd}]
  (if (:help opts)
    (print-help cmd)
    (add-alias opts :nrepl (nrepl-alias))))

(defn build-alias [_opts]
  (let [latest-tag (git/latest-github-tag 'clojure/tools.build)
        tag (:name latest-tag)
        sha (-> latest-tag :commit :sha (subs 0 7))
        s (format "
{:deps {io.github.clojure/tools.build {:git/tag \"%s\" :git/sha \"%s\"}
        slipset/deps-deploy {:mvn/version \"0.2.0\"}}
 :ns-default build}"
                  tag sha)]
    {:s s
     :tag tag
     :sha sha}))

(defn build-file
  [_opts]
  (let [base "(ns build
  (:require [clojure.tools.build.api :as b]
            [clojure.edn :as edn]))

(def project (-> (edn/read-string (slurp \"deps.edn\"))
                 :aliases :neil :project))
(def lib (or (:name project) 'my/lib1))

;; use neil project set version 1.2.0 to update the version in deps.edn

(def version (or (:version project)
                 \"1.2.0\"))
(def class-dir \"target/classes\")
(def basis (b/create-basis {:project \"deps.edn\"}))
(def uber-file (format \"target/%s-%s-standalone.jar\" (name lib) version))
(def jar-file (format \"target/%s-%s.jar\" (name lib) version))

(defn clean [_]
  (b/delete {:path \"target\"}))

(defn jar [_]
  (b/write-pom {:class-dir class-dir
                :lib lib
                :version version
                :basis basis
                :src-dirs [\"src\"]})
  (b/copy-dir {:src-dirs [\"src\" \"resources\"]
               :target-dir class-dir})
  (b/jar {:class-dir class-dir
          :jar-file jar-file}))

(defn install [_]
  (jar {})
  (b/install {:basis basis
              :lib lib
              :version version
              :jar-file jar-file
              :class-dir class-dir}))

(defn uber [_]
  (clean nil)
  (b/copy-dir {:src-dirs [\"src\" \"resources\"]
               :target-dir class-dir})
  (b/compile-clj {:basis basis
                  :src-dirs [\"src\"]
                  :class-dir class-dir})
  (b/uber {:class-dir class-dir
           :uber-file uber-file
           :basis basis}))

(defn deploy [opts]
  (jar opts)
  ((requiring-resolve 'deps-deploy.deps-deploy/deploy)
    (merge {:installer :remote
                       :artifact jar-file
                       :pom-file (b/pom-path {:lib lib :class-dir class-dir})}
                    opts))
  opts)

"]
    base))

(defn add-build [{:keys [opts] :as cmd}]
  (if (:help opts)
    (print-help cmd)
    (do
      (if-not (fs/exists? "build.clj")
        (spit "build.clj" (build-file opts))
        (println "[neil] Project build.clj already exists."))
      (ensure-deps-file opts)
      (let [ba (build-alias opts)]
        (when (= ::update (add-alias opts :build (:s ba)))
          (println "[neil] Updating tools build to newest git tag + sha.")
          (let [edn-string (edn-string opts)
                edn (edn/read-string edn-string)
                build-alias (get-in edn [:aliases :build :deps 'io.github.clojure/tools.build])
                [tag-key sha-key]
                (cond (and
                       (:tag build-alias)
                       (:sha build-alias))
                      [:tag :sha]
                      (and
                       (:git/tag build-alias)
                       (:git/sha build-alias))
                      [:git/tag :git/sha])]
            (when (and tag-key sha-key)
              (let [nodes (edn-nodes edn-string)
                    nodes (r/assoc-in nodes [:aliases :build :deps 'io.github.clojure/tools.build tag-key]
                                      (:tag ba))
                    nodes (r/assoc-in nodes [:aliases :build :deps 'io.github.clojure/tools.build sha-key]
                                      (:sha ba))
                    s (str (str/trim (str nodes)) "\n")]
                (spit (:deps-file opts) s)))))))))

(defn print-dep-add-help []
  (println "Usage: neil add dep [lib] [options]")
  (println "Options:")
  (println (cli/format-opts
            {:spec spec
             :order [:lib :version :sha :latest-sha :deps/root :as :alias :deps-file]})))

(defn dep-add [{:keys [opts]}]
  (if (or (:help opts) (:h opts) (not (:lib opts)))
    (print-dep-add-help)
    (do
      (ensure-deps-file opts)
      (let [edn-string (edn-string opts)
            edn-nodes (edn-nodes edn-string)
            lib (:lib opts)
            lib (symbol lib)
            alias (:alias opts)
            explicit-git? (or (:sha opts)
                              (:latest-sha opts))
            [version git?] (if explicit-git?
                             [(or (:sha opts)
                                  (git/latest-github-sha lib)) true]
                             (or
                              (when-let [v (:version opts)]
                                [v false])
                              (when-let [v (latest-clojars-version lib)]
                                [v false])
                              (when-let [v (latest-mvn-version lib)]
                                [v false])
                              (when-let [v (git/latest-github-sha lib)]
                                [v true])))
            mvn? (not git?)
            git-url (when git?
                      (or (:git/url opts)
                          (str "https://github.com/" (git/clean-github-lib lib))))
            as (or (:as opts) lib)
            existing-aliases (-> edn-string edn/read-string :aliases)
            path (if alias
                   [:aliases
                    alias
                    (if (get-in existing-aliases [alias :deps]) :deps :extra-deps)
                    as]
                   [:deps as])
            nl-path (if (and alias
                             (not (contains? existing-aliases alias)))
                      [:aliases alias]
                      path)
            ;; force newline in
            ;; [:deps as] if no alias
            ;; [:aliases alias] if alias DNE
            ;; [:aliases alias :deps as] if :deps present
            ;; [:aliases alias :extra-deps as] if alias exists
            edn-nodes (-> edn-nodes (r/assoc-in nl-path nil) str r/parse-string)
            nodes (cond
                    mvn?
                    (r/assoc-in edn-nodes path
                                {:mvn/version version})
                    git?
                    ;; multiple steps to force newlines
                    (-> edn-nodes
                        (r/assoc-in
                         (conj path :git/url) git-url)
                        str
                        r/parse-string
                        (r/assoc-in
                         (conj path :git/sha) version)))
            nodes (if-let [root (and git? (:deps/root opts))]
                    (-> nodes
                        (r/assoc-in (conj path :deps/root) root))
                    nodes)
            s (str (str/trim (str nodes)) "\n")]
        (spit (:deps-file opts) s)))))

(defn dep-versions [{:keys [opts]}]
  (let [lib (:lib opts)
        lib (symbol lib)
        versions (or (seq (clojars-versions lib opts))
                     (seq (mvn-versions lib opts)))]
    (if-not versions
      (binding [*out* *err*]
        (println "Unable to find" lib "on Clojars or Maven.")
        (System/exit 1))
      (doseq [v versions]
        (println :lib lib :version v)))))

(defn print-dep-search-help []
  (println (str/trim "
Usage: neil dep search [lib]

Search Clojars for a string in any attribute of an artifact:

  $ neil dep search \"babashka.nrepl\"
  :lib babashka/babashka.nrepl :version 0.0.6

Note that Clojars stores the namespace and name of a library as separate
attributes, so searching for a ns-qualified library will not necessarily
return any matches:

  $ neil dep search \"babashka/babashka.nrepl\"
  Unable to find babashka/babashka.nrepl on Clojars.

But a search string can be matched in a library's description:

$ neil dep search \"test framework\"

will return libraries with 'test framework' in their description.")))

(defn dep-search [{:keys [opts]}]
  (if (or (:help opts) (not (:search-term opts)))
    (print-dep-search-help)
    (let [search-term (:search-term opts)
          url (str "https://clojars.org/search?format=json&q=\"" (url-encode search-term) "\"")
          {search-results :results
           results-count :count} (curl-get-json url)]
      (when (zero? results-count)
        (binding [*out* *err*]
          (println "Unable to find" search-term  "on Clojars.")
          (System/exit 1)))
      (doseq [search-result search-results]
        (println :lib (format  "%s/%s"
                               (:group_name search-result)
                               (:jar_name search-result))
                 :version (:version search-result)
                 :description (pr-str (:description search-result)))))))

(defn print-help [_]
  (println (str/trim "
Usage: neil <subcommand> <options>

Most subcommands support the options:
  --alias      Override alias name.
  --deps-file  Override deps.edn file name.

Subcommands:

add
  dep    Alias for `neil dep add`.
  test   adds cognitect test runner to :test alias.
  build  adds tools.build build.clj file and :build alias.
  kaocha adds kaocha test runner to :koacha alias.
  nrepl  adds nrepl server to :nrepl alias.

dep
  add: Adds --lib, a fully qualified symbol, to deps.edn :deps.
    Run `neil dep add --help` to see all options.

  search: Search Clojars for a string in any attribute of an artifact
    Run `neil dep search --help` to see all options.

new:
  Create a project using deps-new
    Run neil new --help to see all options.

test:
  Run tests. Assumes `neil add test`. Run `neil test --help` to see all options.

license
  list   Lists commonly-used licenses available to be added to project. Takes an optional search string to filter results.
  search Alias for `list`
  add    Writes license text to a file
    Options:
    --license The key of the license to use (e.g. epl-1.0, mit, unlicense). --license option name may be elided when license key is provided as first argument.
    --file    The file to write. Defaults to 'LICENSE'.
")))

;; licenses
(def licenses-api-url "https://api.github.com/licenses")

(defn license-search [{:keys [opts]}]
  (let [search-term (:search-term opts)
        license-vec (->> (str licenses-api-url "?per_page=50")
                         curl-get-json
                         (map #(select-keys % [:key :name])))
        search-results (if search-term
                         (filter #(str/includes?
                                   (str/lower-case (:name %))
                                   (str/lower-case search-term))
                                 license-vec)
                         license-vec)]
    (if (empty? search-results)
      (binding [*out* *err*]
        (println "No licenses found")
        (System/exit 1))
      (doseq [result search-results]
        (println :license (:key result) :name (pr-str (:name result)))))))

(defn license-to-file [{:keys [opts]}]
  (let [license-key (:license opts)
        output-file (or (:file opts) "LICENSE")
        {:keys [message name body]} (some->> license-key url-encode
                                             (str licenses-api-url "/")
                                             curl-get-json)]
    (cond
      (not license-key) (throw (ex-info "No license key provided." {}))
      (= message "Not Found")
      (throw (ex-info (format "License '%s' not found." license-key) {:license license-key}))
      (not body)
      (throw (ex-info (format "License '%s' has no body text." (or name license-key))
                      {:license license-key}))
      :else (spit output-file body))))

(defn add-license [opts]
  (try
    (license-to-file opts)
    (catch Exception e
      (binding [*out* *err*]
        (println (ex-message e))
        (System/exit 1)))))

(defn neil-test [{:keys [opts]}]
  (neil-test/neil-test opts))

(defn -main [& _args]
  (cli/dispatch
   [{:cmds ["add" "dep"] :fn dep-add :args->opts [:lib]}
    {:cmds ["add" "test"] :fn add-cognitect-test-runner}
    {:cmds ["add" "build"] :fn add-build}
    {:cmds ["add" "kaocha"] :fn add-kaocha}
    {:cmds ["add" "nrepl"] :fn add-nrepl}
    {:cmds ["dep" "versions"] :fn dep-versions :args->opts [:lib]}
    {:cmds ["dep" "add"] :fn dep-add :args->opts [:lib]}
    {:cmds ["dep" "search"] :fn dep-search :args->opts [:search-term]}
    {:cmds ["license" "list"] :fn license-search :args->opts [:search-term]}
    {:cmds ["license" "search"] :fn license-search :args->opts [:search-term]}
    {:cmds ["license" "add"] :fn add-license :args->opts [:license]}
    {:cmds ["new"] :fn new/run-deps-new
     :args->opts [:template :name :target-dir]
     :spec {:name {:coerce proj/coerce-project-name}}}
    {:cmds ["version"] :fn neil-version/neil-version :aliases {:h :help}}
    {:cmds ["help"] :fn print-help}
    {:cmds ["test"] :fn neil-test
     ;; TODO: babashka CLI doesn't support :coerce option directly here
     :spec neil-test/neil-test-spec
     :alias neil-test/neil-test-aliases}
    {:cmds [] :fn (fn [{:keys [opts] :as m}]
                    (if (:version opts)
                      (neil-version/print-version)
                      (print-help m)))}]
   *command-line-args*
   {:spec spec
    :exec-args {:deps-file "deps.edn"}})
  nil)

(when (= *file* (System/getProperty "babashka.file"))
  (-main))
