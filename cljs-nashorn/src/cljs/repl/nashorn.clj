(ns cljs.repl.nashorn
  (:refer-clojure :exclude [loaded-libs])
  (:require [clojure.string :as string]
            [clojure.java.io :as io]
            [cljs.compiler :as comp]
            [cljs.analyzer :as ana]
            [cljs.repl :as repl])
  (:import cljs.repl.IJavaScriptEnv
           javax.script.ScriptEngineManager
           javax.script.ScriptContext
           jdk.nashorn.api.scripting.NashornException))

(def ^String bootjs (str "goog.require = function(rule){"
                         "Packages.clojure.lang.RT[\"var\"]"
                         "(\"cljs.repl.nashorn\",\"goog-require\")"
                         ".invoke(___repl_env, rule);}"))

(defprotocol IEval
  (-eval [this env filename line]))

(extend-protocol IEval
  java.lang.String
  (-eval [^java.lang.String this {:keys [engine]} filename line]
    (.eval engine this))

  java.io.Reader
  (-eval [^java.io.Reader this {:keys [engine]} filename line]
    (.eval engine this)))

(defn goog-require [repl-env rule]
  (when-not (contains? @(:loaded-libs repl-env) rule)
    (let [path (string/replace (comp/munge rule) \. java.io.File/separatorChar)
          cljs-path (str path ".cljs")
          js-path (str "goog/"
                       (-eval (str "goog.dependencies_.nameToPath['" rule "']")
                              repl-env "<cljs repl>" 1))]
      (if-let [res (io/resource cljs-path)]
        (binding [ana/*cljs-ns* 'cljs.user]
          (repl/load-stream repl-env cljs-path res))
        (if-let [res (io/resource js-path)]
          (with-open [reader (io/reader res)]
            (-eval reader repl-env js-path 1))
          (throw (Exception. (str "Cannot find " cljs-path " or "
                                  js-path " in classpath")))))
      (swap! (:loaded-libs repl-env) conj rule))))

(defn load-resource
  "Load a JS file from the classpath into the REPL environment."
  [env filename]
  (let [resource (io/resource filename)]
    (assert resource (str "Can't find " filename " in classpath"))
    (-eval (slurp resource) env filename 1)))

(defmulti stacktrace class)
(defmethod stacktrace :default [e]
  (apply str (interpose "\n" (map #(str "        " (.toString %))
                                  (.getStackTrace e)))))

(defmulti eval-result class)
(defmethod eval-result :default [r] (.toString r))
(defmethod eval-result nil [_] "")

(defn nashorn-setup [this]
  (let [env (ana/empty-env)]
    (repl/load-file this "cljs/core.cljs")
    (swap! (:loaded-libs this) conj "cljs.core")
    (repl/evaluate-form this env "<cljs repl>" '(ns cljs.user))
    (repl/evaluate-form this env "<cljs repl>"
                        '(set! *print-fn*
                               (fn [x]
                                 (.print java.lang.System/out x))))))

(defn nashorn-eval [this filename line js]
  (try
    {:status :success
     :value (eval-result (-eval js this filename line))}
    (catch Throwable e
      {:status :exception
       :value (.toString e)
       :stacktrace (stacktrace e)})))

(defn nashorn-load [this ns url]
  (let [missing (remove #(contains? @(:loaded-libs this) %) ns)]
    (when (seq missing)
      (do
        (try
          (-eval (io/reader url) this (.toString url) 1)
          (catch Throwable e (println "Error loading script resource " url
                                      "\n" e)))
        (swap! (:loaded-libs this) (partial apply conj) missing)))))

(defrecord NashornEnv [loaded-libs]
  repl/IJavaScriptEnv
  (-setup [this]
    (nashorn-setup this))

  (-evaluate [this filename line js]
    (nashorn-eval this filename line js))

  (-load [this ns url]
    (nashorn-load this ns url))

  (-tear-down [this]))

(defn repl-env
  "Create a Nashorn REPL environment."
  [& {:as opts}]

  (let [engine (.getEngineByName (ScriptEngineManager.) "Nashorn")
        scope (.getBindings engine ScriptContext/GLOBAL_SCOPE)
        env (merge (NashornEnv. (atom #{})) {:engine engine})]
    (.put scope "___repl_env" env)
    (load-resource env "goog/base.js")
    (-eval bootjs env "bootjs" 1)
    (load-resource env "goog/deps.js")
    env))

(defn run-nashorn-repl []
  (repl/repl (repl-env)))
