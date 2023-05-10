(ns build
  (:use tupelo.core)
  (:require
    [clojure.tools.build.api :as b]
    [deps-deploy.deps-deploy :as dd]
    [schema.core :as s]
    [tupelo.misc :as misc]
    [tupelo.schema :as tsk]
    [tupelo.string :as str]
    ))

(def opts-default
  {:version-str   "23.05.04" ; snapshot versions MUST look like `23.03.03-SNAPSHOT` (i.e. no letters like `-03a`)
   :lib-name      'mygroup/myartifact ; must be a namespaced-qualified symbol, interpreted as `group-id/artifact-id` for Maven
   :scm-root      "github.com/myorg/myproj" ; must match :lib-name above
   :src-dirs      ["src"]
   :resource-dirs ["resources"]
   :build-folder  "target"
   })

(s/defn validate-opts
  [opts-in :- tsk/KeyMap]
  (let [opts (glue opts-default opts-in)]
    (with-map-vals opts [version-str lib-name scm-root src-dirs resource-dirs build-folder]
      ; hard-wired sanity checks
      (assert (= src-dirs ["src"]))
      (assert (= resource-dirs ["resources"]))
      (assert (= build-folder "target"))
      (assert (string? version-str))
      (assert (string? scm-root))
      (assert (symbol? lib-name)))
    opts))

(defn clean-files
  "Delete all compiler output files (i.e. `.target/**/*`)"
  [ctx & others] ; ignore `nil` arg
  (with-map-vals ctx [build-folder ]
    (b/delete {:path build-folder})
    (println (format "Build folder \"%s\" removed" build-folder))))

(defn check-all-committed-or-throw
  "Use git to verify there are no uncommitted files present"
  [ctx & others] ; ignore `nil` arg
  (let [cmd-str-1     "git status --short --branch"
        shell-result  (misc/shell-cmd cmd-str-1)
        out-lines     (str/split-lines (grab :out shell-result))
        out-lines-num (count out-lines)]
    ; git always returns the branch as the first line like "## master...origin/master"
    ; So, there are modified uncommitted files if count is larger than 1
    (when (< 1 out-lines-num)
      (throw (ex-info "Error: Uncommitted files detected" shell-result)))))

(defn tag-release
  "Tag release by prepending a `v` char to the version string and calling `git tag`
    (eg version `23.03.15` => tag `v23.03.15`)."
  [ctx & others] ; ignore `nil` arg
  (with-map-vals ctx [version-str lib-name scm-root src-dirs resource-dirs build-folder
                      git-tag-str jar-content jar-file-name basis]
    (check-all-committed-or-throw ctx)
    (println (str/quotes->double
               (format "Tagging release: '%s'" git-tag-str)))
    (let [cmd-str-1 (str/quotes->double
                      (format "git tag --force '%s' -m'%s'" git-tag-str git-tag-str))
          r1        (misc/shell-cmd cmd-str-1)]
      (when (not= 0 (grab :exit r1))
        (throw (ex-info "git tag failed " r1))))
    (println "Pushing release & tags...")
    (let [cmd-str-2 "git pull ; git push ; git push --tags --force"
          r2        (misc/shell-cmd cmd-str-2)]
      (when (not= 0 (grab :exit r2))
        (throw (ex-info "git push failed " r2))))))

(defn build-jar
  "Build a new, clean JAR file from source-code."
  [ctx & others] ; ignore `nil` arg
  (newline)
  (clean-files ctx)
  (tag-release ctx)
  (with-map-vals ctx [version-str lib-name scm-root src-dirs resource-dirs build-folder
                      git-tag-str jar-content jar-file-name basis]

    ; prepare jar content
    (b/copy-dir {:src-dirs   src-dirs
                 :target-dir jar-content})

    (b/write-pom {:class-dir     jar-content ; create pom.xml
                  :lib           lib-name
                  :version       version-str
                  :basis         basis
                  :resource-dirs resource-dirs
                  :scm           {:tag                 git-tag-str
                                  :url                 (str "https://" scm-root)
                                  :connection          (str "scm:git:git://" scm-root ".git")
                                  :developerConnection (str "scm:git:ssh://git@" scm-root ".git")}

                  ; #todo throw if "src" is not root of project source code
                  ; #todo is this really needed?
                  :src-dirs      ["src"] ; ***** all but first dir will be discarded *****
                  })

    (b/jar {:class-dir jar-content ; create jar
            :jar-file  jar-file-name})
    (println (format "Jar file created: \"%s\"" jar-file-name))))

(defn deploy-clojars
  "Build & deploy a source-code JAR file to clojars.org"
  [opts & others] ; ignore `nil` arg
  (newline)
  (spyx-pretty :deploy-clojars--opts opts)
  (let [opts (validate-opts opts)]
    (with-map-vals opts [version-str lib-name scm-root src-dirs resource-dirs build-folder ]
      (let [git-tag-str   (str "v" version-str) ; a git tag like `v23.01.31` will be added
            jar-content   (str build-folder "/classes") ; folder where we collect files to pack in a jar
            basis         (b/create-basis {:project "deps.edn"}) ; basis structure (read details in the article)
            jar-file-name (format "%s/%s-%s.jar" build-folder (name lib-name) version-str) ; eg `target/tupelo-23.05.04.jar`

            ctx           (glue opts (vals->map git-tag-str jar-content jar-file-name basis))]
        ; (spyx-pretty :deploy-clojars--ctx ctx)
        (build-jar ctx)
        (dd/deploy {:installer :remote
                    :artifact  jar-file-name
                    :pom-file  (b/pom-path {:lib       lib-name
                                            :class-dir jar-content})})))))
