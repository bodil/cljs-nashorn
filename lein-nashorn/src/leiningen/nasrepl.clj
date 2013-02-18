(ns leiningen.nasrepl
  (:use [leinjacker.eval :only [in-project]])
  (:require [leiningen.core.main :as main]
            [leiningen.trampoline :as ltrampoline]
            [leinjacker.deps :as deps]))

(defmacro require-trampoline [& forms]
  `(if ltrampoline/*trampoline?*
     (do ~@forms)
     (do
       (println "You can't run this directly; use \"lein trampoline nasrepl\"")
       (main/abort))))

(defn nasrepl
  "Launch a ClojureScript REPL on Nashorn."
  [project & args]
  (require-trampoline
   (let [project (deps/add-if-missing project '[org.bodil/cljs-nashorn "0.1.1"])]
     (in-project project []
                 (ns (:require [cljs.repl.nashorn :as nashorn]))
                 (nashorn/run-nashorn-repl)))))
