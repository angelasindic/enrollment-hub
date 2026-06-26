workspace "Enrollment Hub System" "C4 — System Context and Containers" {

    !identifiers hierarchical

    model {

        # ── Actors ──────────────────────────────────────────────────────────
        newUser      = person "New user"       "First-time visitor not signed up"
        userWithId   = person "User with ID"   "Visitor with existing credentials, not signed in"

        # ── External software systems ────────────────────────────────────────
        registrationPortal = softwareSystem "Registration Portal" {
            description "Manages user onboarding: sign-up, sign-in, credit card validation, and eIDAS identity verification. Ensures PCI-DSS scoped data never reaches the Enrollment Hub."
            tags "External"
        }

        identityProvider = softwareSystem "Identity Provider" {
            description "User Identity management and token issuance(OIDC/OAuth2) with additional eIDAS 2.0 authentication flow"
            tags "External"
        }


        paymentCheckService = softwareSystem "Payment Check Service" {
            description "Verifies credit card details and issues a signed credit_card_check assertion (CREDIT_CARD route)"
            tags "External"
        }

        eidasService = softwareSystem "eIDAS Identity Service" {
            description "Issues a signed eidas_identity assertion (INVOICE route) — a trust root distinct from the OIDC issuer"
            tags "External"
        }

        fraudService = softwareSystem "Fraud Service" {
            description "Applies internal fraud rules to an account owned by a certain entity"
            tags "External"
        }

        accountService = softwareSystem "Account Service" {
            description "Provides features to manage accounts, including enrollment"
            tags "External"
        }


        # ── Enrollment Hub (with C4 L2 containers) ───────────────
        enrollmentHub = softwareSystem "Enrollment Hub" {
            description "Orchestrates fraud and risk signals for enrollment. Operates outside PCI-DSS scope — no payment card data is processed or stored"

            gateway = container "Gateway" {
                description "Authenticating edge: drives OIDC authorization_code login against the Identity Provider, holds the user session, and relays the access token downstream. Routes only; the relayed JWT is validated by the resource server, not here."
                technology "Spring Cloud Gateway"
            }

            decisionEngine = container "Decision Engine" {
                description "Decides on account enrollment: coordinates geo scoring and fraud evaluation, applies configurable risk rules to handle signal timeouts and geo scoring thresholds, and publishes the outcome"
                technology "Spring Boot"
            }

            geoScoring = container "Geo Scoring" {
                description "Resolves and scores geolocation data for fraud signals"
                technology "Spring Boot"
            }

            nominatim = container "Nominatim" {
                description "Provides geolocation and address resolution; uses OpenStreetMap data for geocoding."
                tags "External"
            }

            libpostal = container "Libpostal" {
                description "Converts free-form addresses into normalized forms for consistent geocoding lookup"
                tags "External"
            }


            # ── Data stores ─────────────────────────────────────────────────
            enrollmentStore = container "Enrollment Store" {
                description "Stores enrollment details and risk signals"
                technology "PostgreSQL"
                tags "Database"
            }

            geoIndexCache = container "Geo-Index Cache" {
                description "Caches geolocation index data for fast lookup"
                technology "Redis"
                tags "Database"
            }

            geoStoringCache = container "Geo-Storing Cache" {
                description "Stores geolocation data per member with TTL"
                technology "Redis"
                tags "Database"
            }
        }

        # ── L1 Relationships ─────────────────────────────────────────────────
        newUser      -> registrationPortal "Sign up"
        userWithId   -> registrationPortal "Sign in"

        registrationPortal -> enrollmentHub      "Initiate account enrollment"
        registrationPortal -> identityProvider "Register / Login (OIDC)"
        registrationPortal -> paymentCheckService "Verify credit card details"
        registrationPortal -> eidasService "Verify eIDAS identity"

        enrollmentHub.gateway -> identityProvider       "Authenticates users via OIDC (authorization_code)"
        enrollmentHub.decisionEngine -> identityProvider "Fetch signing keys (JWKS) to validate access tokens"
        enrollmentHub.decisionEngine -> paymentCheckService "Validate credit-card-check token (JWKS)"
        enrollmentHub.decisionEngine -> eidasService "Validate eIDAS identity token (JWKS)"

        # ── L2 Relationships (internal containers) ───────────────────────────
        enrollmentHub.gateway -> enrollmentHub.decisionEngine "Routes request; relays access token (TokenRelay) + prerequisite token (X-Prerequisite-Token)"


        enrollmentHub.decisionEngine -> enrollmentHub.geoScoring        "Sends scoring request"    "RabbitMQ"
        enrollmentHub.decisionEngine -> accountService "Sends account creation request with decision score" "RabbitMQ"
        enrollmentHub.decisionEngine -> enrollmentHub.enrollmentStore      "Reads/writes enrollment data"

        enrollmentHub.decisionEngine -> fraudService "Sends fraud evaluation request" "RabbitMQ"
        fraudService -> enrollmentHub.decisionEngine "Sends fraud evaluation score reply" "RabbitMQ"

        enrollmentHub.geoScoring     -> enrollmentHub.decisionEngine    "Sends scoring reply"  "RabbitMQ"
        enrollmentHub.geoScoring     -> enrollmentHub.geoStoringCache   "Reads/writes geo data"
        enrollmentHub.geoScoring     -> enrollmentHub.geoIndexCache     "Reads/writes geo-index"
        enrollmentHub.geoScoring     -> enrollmentHub.nominatim                        "Resolve geolocation based on address" "HTTPS"
        enrollmentHub.geoScoring     -> enrollmentHub.libpostal                        "Normalize address"  "HTTPS"


    }

    views {

        systemLandscape "Landscape" {
            include *
            autolayout tb 300 100
            title "Enrollment Hub — system landscape (C4 L1)"
        }

        systemContext enrollmentHub "EnrollmentHubContext" {
            include *
            autolayout tb 300 100
            title "Enrollment Hub — system context (C4 L1)"
        }

        container enrollmentHub "EnrollmentHubContainers" {
            include *
            autolayout tb 300 100
            title "Enrollment Hub — containers (C4 L2)"
        }

        styles {
            element "Person" {
                shape Person
                background "#1168BD"
                color "#ffffff"
            }
            element "Software System" {
                background "#438DD5"
                color "#ffffff"
            }
            element "Container" {
                background "#438DD5"
                color "#ffffff"
            }
            element "External" {
                background "#d3d5db"
                color "#000000"
                border Dashed
            }
            element "Database" {
                shape Cylinder
                background "#438DD5"
                color "#ffffff"
            }
            element "Phase1" {
                border Solid
            }
            element "Phase2" {
                border Dashed
            }
        }

    }
}
