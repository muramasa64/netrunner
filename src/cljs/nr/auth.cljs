(ns nr.auth
  (:require-macros [cljs.core.async.macros :refer [go]])
  (:require [cljs.core.async :refer [chan put!] :as async]
            [nr.ajax :refer [POST GET]]
            [nr.appstate :refer [app-state]]
            [reagent.core :as r]))

(defn authenticated [f]
  (if-let [user (:user @app-state)]
    (f user)
    (.modal (js/$ "#login-form") "show")))

(defn handle-post [event url s]
  (.preventDefault event)
  (swap! s assoc :flash-message "")
  (let [params (-> event .-target js/$ .serialize)
        _ (.-serialize (js/$ (.-target event)))] ;; params is nil when built in :advanced mode. This fixes the issue.
    (go (let [response (<! (POST url params))]
          (if (and (= (:status response) 200) (= (:owner "/forgot") "/forgot") )
            (swap! s assoc :flash-message "Reset password sent")
            (case (:status response)
              401 (swap! s assoc :flash-message "Invalid login or password")
              421 (swap! s assoc :flash-message "No account with that email address exists")
              422 (swap! s assoc :flash-message  "Username taken")
              423 (swap! s assoc :flash-message  "Username too long")
              424 (swap! s assoc :flash-message "Email already used")
              (-> js/document .-location (.reload true))))))))

(defn handle-logout [event]
  (.preventDefault event)
  (go (let [response (<! (POST "/logout" nil))]
        (-> js/document .-location (.reload true)))))

(defn avatar [{:keys [emailhash username]} opts]
  (when emailhash
    [:img.avatar
     {:src (str "https://www.gravatar.com/avatar/" emailhash "?d=retro&s=" (get-in opts [:opts :size]))
      :alt username}]))

(defn logged-menu [user]
  [:ul
   [:li.dropdown.usermenu
    [:a.dropdown-toggle {:href "" :data-toggle "dropdown"}
     [avatar user {:opts {:size 22}}]
     (:username user)
     [:b.caret]]
    [:div.dropdown-menu.blue-shade.float-right
     (when (:isadmin user)
       [:a.block-link "[Admin]"])
     (when (:ismoderator user)
       [:a.block-link "[Moderator]"])
     [:a.block-link {:href "/account"} "Settings"]
     [:a.block-link {:on-click #(handle-logout %)} "Logout"]]]])

(defn unlogged-menu []
  [:ul
   [:li
    [:a {:href "" :data-target "#register-form" :data-toggle "modal"
         :on-click (fn [] .focus (js/$ "input[name='email']"))} "Sign up"]]
   [:li
    [:a {:href "" :data-target "#login-form" :data-toggle "modal"} "Login"]]])

(defn check-username [value s]
  (go (let [response (<! (GET (str "/check/" value)))]
        (case (:status response)
          422 (swap! s assoc :flash-message "Username taken")
          423 (swap! s assoc :flash-message "Username too short/too long")
          (swap! s assoc :flash-message "")))))

(defn check-email [value s]
  (go (let [response (<! (GET (str "/check/" value)))]
        (if (= (:status response) 421)
          (swap! s assoc :flash-message "No account with that email address exists")
          (swap! s assoc :flash-message "")))))

(defn valid-email? [email]
  (let [pattern #"[a-z0-9!#$%&'*+/=?^_`{|}~-]+(?:\.[a-z0-9!#$%&'*+/=?^_`{|}~-]+)*@(?:[a-z0-9](?:[a-z0-9-]*[a-z0-9])?\.)+[a-z0-9](?:[a-z0-9-]*[a-z0-9])?"]
    (and (string? email) (re-matches pattern (.toLowerCase email)))))

(defn register [event s]
  (.preventDefault event)
   (cond
     (empty? (:email @s)) (swap! s assoc :flash-message "Email can't be empty")
     (empty? (:username @s)) (swap! s assoc :flash-message "Username can't be empty")
     (not (-> @s :email valid-email?)) (swap! s assoc :flash-message "Please enter a valid email address")
     (< 20 (-> @s :username count)) (swap! s assoc :flash-message "Username must be 20 characters or shorter")
     (empty? (:password @s)) (swap! s assoc :flash-message "Password can't be empty")
     :else (handle-post event "/register" s)))

(defn register-form []
  (r/with-let [s (r/atom {:flash-message ""})]
    [:div#register-form.modal.fade {:ref "register-form"}
     [:div.modal-dialog
      [:h3 "Create an account"]
      [:p.flash-message (:flash-message @s)]
      [:form {:on-submit #(register % s)}
       [:p [:input {:type "text" :placeholder "Email" :name "email" :ref "email" :value (:email @s)
                    :on-change #(swap! s assoc :email (-> % .-target .-value))
                    :on-blur #(when-not (valid-email? (.. % -target -value))
                                (swap! s assoc :flash-message "Please enter a valid email address"))}]]
       [:p [:input {:type "text" :placeholder "Username" :name "username" :ref "username" :value (:username @s)
                    :on-change #(swap! s assoc :username (-> % .-target .-value))
                    :on-blur #(check-username (-> % .-target .-value) s) :maxLength "16"}]]
       [:p [:input {:type "password" :placeholder "Password" :name "password" :ref "password"
                    :value (:password @s) :on-change #(swap! s assoc :password (-> % .-target .-value))}]]
       [:p [:button "Sign up"]
        [:button {:data-dismiss "modal"} "Cancel"]]]
      [:p "Already have an account? " \
       [:span.fake-link {:on-click #(.modal (js/$ "#login-form") "show")
                         :data-dismiss "modal"} "Log in"]]
      [:p "Need to reset your password? "
       [:span.fake-link {:on-click #(.modal (js/$ "#forgot-form") "show")
                         :data-dismiss "modal"} "Reset"]]]]))

(defn forgot-form []
  (r/with-let [s (r/atom {:flash-message ""})]
    [:div#forgot-form.modal.fade {:ref "forgot-form"}
     [:div.modal-dialog
      [:h3 "Reset your Password"]
      [:p.flash-message (:flash-message @s)]
      [:form {:on-submit #(handle-post % "/forgot" s)}
       [:p [:input {:type "text" :placeholder "Email" :name "email"
                    :on-blur #(check-email (-> % .-target .-value) s)}]]
       [:p [:button "Submit"]
        [:button {:data-dismiss "modal"} "Cancel"]]
       [:p "No account? "
        [:span.fake-link {:on-click #(.modal (js/$ "#register-form") "show")
                          :data-dismiss "modal"} "Sign up!"]]]]]))

(defn login-form []
  (r/with-let [s (r/atom {:flash-message ""})]
    [:div#login-form.modal.fade
     [:div.modal-dialog
      [:h3 "Log in"]
      [:p.flash-message (:flash-message @s)]
      [:form {:on-submit #(handle-post % "/login" s)}
       [:p [:input {:type "text" :placeholder "Username" :name "username"}]]
       [:p [:input {:type "password" :placeholder "Password" :name "password"}]]
       [:p [:button "Log in"]
        [:button {:data-dismiss "modal"} "Cancel"]]
       [:p "No account? "
        [:span.fake-link {:on-click #(.modal (js/$ "#register-form") "show")
                          :data-dismiss "modal"} "Sign up!"]]
       [:p "Forgot your password? "
        [:span.fake-link {:on-click #(.modal (js/$ "#forgot-form") "show")
                          :data-dismiss "modal"} "Reset"]]]]]))

(defn auth-menu []
   (if-let [user (:user @app-state)]
     [logged-menu user]
     [unlogged-menu]))

(defn auth-forms []
  (when-not (:user @app-state)
    [:div
     [register-form]
     [login-form]
     [forgot-form]]))
