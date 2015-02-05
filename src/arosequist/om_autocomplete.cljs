(ns arosequist.om-autocomplete
  (:require-macros [cljs.core.async.macros :refer [go]])
  (:require [cljs.core.async :refer [chan close! put! take! timeout]]
            [om.core :as om :include-macros true]
            [om.dom :as dom :include-macros true]
            [goog.events :as gevents]
            [clojure.string :as str]))

(defn- wait-on-channel [channel function]
  (go
    (loop []
      (when-some
        [channel-output (<! channel)]
        (function channel-output)
        (recur)))))

(defn- handle-highlight
  [owner idx]
  (let [idx (min idx (count (om/get-state owner :suggestions)))
        idx (max idx -1)]
    (om/set-state! owner :highlighted-index idx)))

(defn- reset-autocomplete-state!
  [owner]
  (do
    (om/set-state! owner :highlighted-index 0)
    (om/set-state! owner :value "")))

(defn- handle-select
  [owner result-ch idx]
  (let [suggestions (om/get-state owner :suggestions)
        item        (get (vec suggestions) idx)]
    (do
      (put! result-ch [idx item])
      (reset-autocomplete-state! owner))))

(defn- container-view [_ _ {:keys [class-name id]}]
  (reify
    om/IRenderState
    (render-state [_ {:keys [input-component results-component mouse-ch]}]
                  (dom/div #js {:id        id
                                :className class-name}
                           input-component results-component))))

(defn- input-view [_ owner {:keys [class-name placeholder id on-key-down]}]
  (reify
    om/IWillMount
    (will-mount [_]
                (let [{:keys [input-focus-ch]} (om/get-state owner)]
                  (go
                    (loop []
                      (when-some [input-focus (<! input-focus-ch)]
                                 (.focus (om/get-node owner "input"))
                                 (recur))))))
    om/IRenderState
    (render-state [_ {:keys [focus-ch value-ch highlight-ch select-ch value highlighted-index mouse? displayed?]}]
                  (dom/input
                    #js {:id id
                         :className class-name
                         :placeholder placeholder
                         :type "text"
                         :autoComplete "off"
                         :spellCheck "false"
                         :value value
                         :ref "input"
                         :onFocus (fn [e]
                                    (put! focus-ch true)
                                    (.preventDefault e))
                         :onBlur (fn [e]
                                   (when (not mouse?)
                                     (put! focus-ch false))
                                   (.preventDefault e))
                         :onKeyDown (fn [e]
                                      (on-key-down
                                        e value
                                        (fn [e]
                                          (let [keyCode (.-keyCode e)]
                                            (case (.-keyCode e)
                                              40 (put! highlight-ch (inc highlighted-index)) ;; up
                                              38 (put! highlight-ch (dec highlighted-index)) ;; down
                                              13 (when displayed? (put! select-ch highlighted-index)) ;; enter
                                              9  (when displayed? (put! select-ch highlighted-index)) ;; tab
                                              nil)
                                            (when (contains? #{40 38 13 9} keyCode) (.preventDefault e))))))
                         :onChange (fn [e]
                                     (put! value-ch (.. e -target -value))
                                     (.preventDefault e))}))))

(defn- loading-default [_ owner {:keys [class-name id]}]
  (reify
    om/IRender
    (render [_]
            (dom/li #js {:id        id
                         :className class-name} (dom/a nil "Loading...")))))


(defn- result-item-default [app owner {:keys [class-name class-name-highlighted id result-text-fn]}]
  (reify
    om/IDidMount
    (did-mount [this]
               (let [{:keys [index highlight-ch select-ch]} (om/get-state owner)
                     node (om/get-node owner)]
                 (gevents/listen node (.-MOUSEOVER gevents/EventType) #(put! highlight-ch index))
                 (gevents/listen node (.-CLICK gevents/EventType) #(put! select-ch index))))

    om/IRenderState
    (render-state [_ {:keys [item index highlighted-index]}]
                  (let [highlighted? (= index highlighted-index)
                        text-fn      (if-not (nil? result-text-fn) result-text-fn identity)]
                    (dom/li #js {:id        id
                                 :className (if highlighted? (str class-name-highlighted " " class-name) class-name)}
                            (dom/a #js {:href "#" :onClick (fn [e] (.preventDefault e))}
                                   (text-fn item index)))))))


(defn- results-view [app _ {:keys [class-name id loading loading-opts result-item result-item-opts]}]
  (reify
    om/IRenderState
    (render-state [_ {:keys [highlight-ch select-ch value loading? focused? mouse-ch suggestions highlighted-index]}]
                  (let [display?         (and focused? value (not= value ""))
                        display-style    (if display? "block" "none")
                        attrs               #js {:id           id
                                                 :className    class-name
                                                 :style        #js {:display display-style}
                                                 :onMouseEnter (fn [e]
                                                                 (put! mouse-ch true)
                                                                 (.preventDefault e))
                                                 :onMouseLeave (fn [e]
                                                                 (put! mouse-ch false)
                                                                 (.preventDefault e))}
                        loading-view     (if-not (nil? loading)     loading     loading-default)
                        result-item-view (if-not (nil? result-item) result-item result-item-default)]
                    (cond
                      (and loading-view loading?)
                      (dom/ul attrs (om/build loading-view app {:opts loading-opts}))
                      (not (empty? suggestions))
                      (apply dom/ul attrs
                             (map-indexed
                               (fn [idx item]
                                 (om/build result-item-view app {:init-state {:highlight-ch highlight-ch
                                                                              :select-ch    select-ch}
                                                                 :state      {:item item
                                                                              :index idx
                                                                              :highlighted-index highlighted-index}
                                                                 :opts       result-item-opts}))
                               suggestions))
                      :otherwise (dom/ul #js {:className class-name :style #js {:display "none"}}))))))

(defn autocomplete
  [cursor owner {:keys [result-ch suggestions-fn result-text-fn container-opts input-opts results-opts]}]
  (reify
    om/IInitState
    (init-state [_]
                {:focus-ch (chan) :value-ch (chan)
                 :highlight-ch (chan) :select-ch (chan)
                 :mouse-ch (chan)
                 :channels {} :highlighted-index 0})
    om/IWillMount
    (will-mount [_]
                (let [{:keys [focus-ch value-ch highlight-ch select-ch mouse-ch]} (om/get-state owner)]
                  (wait-on-channel focus-ch     #(om/set-state! owner :focused? %))
                  (wait-on-channel value-ch     #(om/set-state! owner :value %))
                  (wait-on-channel highlight-ch #(handle-highlight owner %))
                  (wait-on-channel select-ch    #(handle-select owner result-ch %))
                  (wait-on-channel mouse-ch     #(om/set-state! owner :mouse? %))))
    om/IDidUpdate
    (did-update [_ _ old]
                (let [old-value (:value old)
                      new-value (om/get-state owner :value)]
                  (when (not= old-value new-value)
                    (om/update-state!
                      owner
                      (fn [state]
                        (let [old-channels       (:channels state)
                              old-suggestions-ch (:suggestions-ch old-channels)
                              old-cancel-ch      (:cancel-suggestions-ch old-channels)
                              new-suggestions-ch (chan)
                              new-cancel-ch      (chan)]
                          (when old-suggestions-ch (close! old-suggestions-ch))
                          (when old-cancel-ch (close! old-cancel-ch))
                          (take! new-suggestions-ch
                                 (fn [suggestions]
                                   (when suggestions
                                     (om/update-state! owner (fn [s] (assoc s
                                                                       :suggestions suggestions
                                                                       :loading?    false))))))

                          (suggestions-fn new-value new-suggestions-ch new-cancel-ch)

                          (assoc (update-in state [:channels]
                                            #(assoc %
                                               :suggestions-ch new-suggestions-ch
                                               :cancel-suggestions-ch new-cancel-ch))
                            :loading? true)))))))
    om/IWillUnmount
    (will-unmount [_]
                  (let [{:keys [focus-ch value-ch highlight-ch select-ch mouse-ch channels]} (om/get-state owner)]
                    (close! focus-ch)
                    (close! value-ch)
                    (close! highlight-ch)
                    (close! select-ch)
                    (close! mouse-ch)
                    (let [cancel-ch      (:cancel-suggestions-ch channels)
                          suggestions-ch (:suggestions-ch channels)]
                      (when cancel-ch (close! cancel-ch))
                      (when suggestions-ch (close! suggestions-ch)))))
    om/IRenderState
    (render-state [_ {:keys [focus-ch value-ch highlight-ch select-ch mouse-ch value
                             highlighted-index loading? focused? mouse? suggestions]}]
                  (om/build container-view cursor
                            {:state
                             {:input-component
                              (om/build input-view cursor
                                        {:init-state {:focus-ch       focus-ch
                                                      :value-ch       value-ch
                                                      :highlight-ch   highlight-ch
                                                      :select-ch      select-ch
                                                      :input-focus-ch (:input-focus-ch input-opts)}
                                         :state {:value             value
                                                 :highlighted-index highlighted-index
                                                 :displayed?        (> (count suggestions) 0)
                                                 :mouse?            mouse?}
                                         :opts (dissoc input-opts :input-focus-ch)})
                              :results-component
                              (om/build results-view cursor
                                        {:init-state {:highlight-ch highlight-ch
                                                      :select-ch    select-ch
                                                      :mouse-ch     mouse-ch}
                                         :state {:value             value
                                                 :loading?          loading?
                                                 :focused?          focused?
                                                 :suggestions       suggestions
                                                 :highlighted-index highlighted-index}
                                         :opts (assoc-in results-opts [:result-item-opts :result-text-fn] result-text-fn)})}
                             :opts container-opts}))))
