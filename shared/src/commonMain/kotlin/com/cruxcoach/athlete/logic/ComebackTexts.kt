package com.cruxcoach.athlete.logic

import com.cruxcoach.athlete.model.InjuryFinding
import com.cruxcoach.athlete.model.Urgency
import com.cruxcoach.athlete.model.WarningSign

/**
 * Warning signs, findings and typical courses in plain words (research/safety-regulation.md §4, fingers.md §3,
 * other-injuries.md §4–8). Shared so Android and iOS say exactly the same.
 */
object ComebackTexts {

    fun sign(s: WarningSign): Txt = when (s) {
        WarningSign.DEFORMITY -> Txt("It looks deformed, points at an odd angle, or a joint is out of place",
            "Es sieht verformt aus, steht schief oder ein Gelenk ist ausgerenkt")
        WarningSign.NUMB_OR_COLD -> Txt("Numb, tingling that stays, cold, pale or bluish",
            "Taub, anhaltendes Kribbeln, kalt, blass oder bläulich")
        WarningSign.CANNOT_MOVE -> Txt("You can't move it at all", "Du kannst es gar nicht bewegen")
        WarningSign.OPEN_WOUND -> Txt("An open wound over the injury or a joint", "Eine offene Wunde über der Verletzung oder einem Gelenk")
        WarningSign.INFECTION -> Txt("Red, hot and swollen with fever or feeling ill", "Rot, heiß und geschwollen mit Fieber oder Krankheitsgefühl")
        WarningSign.FINGER_HELD_BENT_SWOLLEN -> Txt("A swollen finger held bent that hurts a lot to straighten",
            "Ein geschwollener, gebeugt gehaltener Finger, der beim Strecken stark schmerzt")
        WarningSign.HEAD_AFTER_FALL -> Txt("After a fall: you hit your head and blacked out, are confused, or have new weakness",
            "Nach einem Sturz: Kopf angeschlagen und kurz weg, verwirrt oder neue Schwäche")
        WarningSign.CHEST_PRESSURE -> Txt("Arm pain together with pressure or tightness in the chest",
            "Armschmerz zusammen mit Druck oder Enge in der Brust")
        WarningSign.SADDLE_NUMBNESS -> Txt("Numbness around the genitals or buttocks, or new trouble with bladder or bowel",
            "Taubheit im Genital- oder Gesäßbereich oder neue Probleme mit Blase oder Darm")
        WarningSign.CALF_SWOLLEN_WARM -> Txt("Calf swollen, warm and painful, or short of breath",
            "Wade geschwollen, warm und schmerzhaft, oder Atemnot")
        WarningSign.POP_WITH_SWELLING -> Txt("A pop with swelling or bruising", "Ein Knall mit Schwellung oder Bluterguss")
        WarningSign.BOWSTRINGING -> Txt("The tendon lifts visibly away from the bone when you grip",
            "Die Sehne hebt sich beim Zugreifen sichtbar vom Knochen ab")
        WarningSign.FINGERTIP_CANNOT_BEND -> Txt("Holding the middle joint straight, you can't bend the fingertip on its own",
            "Bei gestrecktem Mittelgelenk kannst du das Fingerendglied nicht allein beugen")
        WarningSign.JOINT_UNSTABLE -> Txt("The joint wobbles sideways, feels loose or won't straighten",
            "Das Gelenk wackelt seitlich, fühlt sich locker an oder lässt sich nicht strecken")
        WarningSign.HEEL_OF_PALM -> Txt("Pain at the heel of the palm on the little-finger side",
            "Schmerz am Handballen auf der Kleinfingerseite")
        WarningSign.THUMB_BASE_AFTER_FALL -> Txt("You fell on your hand and it hurts at the base of the thumb",
            "Auf die Hand gefallen und es schmerzt am Daumenansatz")
        WarningSign.WRIST_CLUNK -> Txt("A painful clunk, a loose feeling, or turning the palm up and down is blocked",
            "Schmerzhaftes Klacken, lockeres Gefühl oder das Drehen der Hand ist blockiert")
        WarningSign.ELBOW_POP -> Txt("A pop at the front of the elbow, a bruise in the crease, weak turning the palm up",
            "Ein Knall vorne am Ellenbogen, Bluterguss in der Beuge, schwach beim Aufdrehen der Hand")
        WarningSign.ELBOW_CANNOT_STRAIGHTEN -> Txt("After a fall you can't fully straighten the elbow",
            "Nach einem Sturz lässt sich der Ellenbogen nicht ganz strecken")
        WarningSign.SHOULDER_CAME_OUT -> Txt("The shoulder came out or had to be put back", "Die Schulter ist herausgesprungen oder musste eingerenkt werden")
        WarningSign.SHOULDER_CANNOT_LIFT -> Txt("Since the injury you can't lift your arm to the side",
            "Seit der Verletzung kannst du den Arm nicht seitlich anheben")
        WarningSign.CANNOT_WALK_FOUR_STEPS -> Txt("You couldn't take four steps right after it, or can't now",
            "Direkt danach oder jetzt sind keine vier Schritte möglich")
        WarningSign.ANKLE_BONE_TENDER -> Txt("Pressing hurts right on the ankle bone (back edge or tip) or on the edge of the midfoot",
            "Druck tut direkt am Knöchel (Hinterkante oder Spitze) oder am Mittelfuß-Rand weh")
        WarningSign.HEEL_AFTER_LANDING -> Txt("You landed on your heels from height and the heel hurts",
            "Aus der Höhe auf die Fersen gelandet und die Ferse schmerzt")
        WarningSign.NO_TIPTOE_AFTER_POP -> Txt("A pop or kick feeling in the calf, and you can't stand on tiptoe",
            "Ein Knall oder Tritt-Gefühl in der Wade, und du kommst nicht auf die Zehenspitzen")
        WarningSign.KNEE_LOCKS_OR_SWELLED -> Txt("The knee locks, gives way or swelled up within hours",
            "Das Knie blockiert, knickt weg oder ist innerhalb von Stunden dick geworden")
        WarningSign.KNEECAP_CAME_OUT -> Txt("The kneecap slipped out of place", "Die Kniescheibe ist herausgesprungen")
        WarningSign.HEEL_HOOK_POP -> Txt("A pop in a heel hook with bruising at the back of the thigh, or pain right at the sit bone",
            "Ein Knall im Heel Hook mit Bluterguss hinten am Oberschenkel oder Schmerz direkt am Sitzbein")
        WarningSign.RADIATING_WITH_WEAKNESS -> Txt("Pain shooting into the arm or leg with numbness or weakness",
            "Ausstrahlender Schmerz in Arm oder Bein mit Taubheit oder Schwäche")
        WarningSign.NIGHT_PAIN_UNWELL -> Txt("Pain at night or at rest that keeps getting worse, fever, weight loss or feeling ill",
            "Nacht- oder Ruheschmerz, der weiter zunimmt, Fieber, Gewichtsverlust oder Krankheitsgefühl")
        WarningSign.BLOOD_THINNERS -> Txt("You take blood thinners and it is clearly swollen or bruised",
            "Du nimmst Blutverdünner und es ist deutlich geschwollen oder blau")
        WarningSign.HAD_IT_BEFORE -> Txt("You had this at the same spot before", "Du hattest das an derselben Stelle schon einmal")
        WarningSign.FINGER_CATCHES -> Txt("The finger catches, locks or has a lump", "Der Finger hakt, blockiert oder hat einen Knoten")
        WarningSign.SEVERAL_FINGERS -> Txt("Several fingers or joints hurt without a clear climbing cause",
            "Mehrere Finger oder Gelenke tun ohne klaren Kletter-Anlass weh")
        WarningSign.TINGLING_COMES_AND_GOES -> Txt("Tingling that comes and goes", "Kribbeln, das kommt und geht")
        WarningSign.SHOULDER_FEELS_UNSTABLE -> Txt("The shoulder feels unstable, or you have 'dead arm' moments",
            "Die Schulter fühlt sich instabil an oder du hast Momente mit 'totem Arm'")
        WarningSign.KNEE_CATCHES -> Txt("The knee catches sometimes but frees again", "Das Knie hakt manchmal, löst sich aber wieder")
        WarningSign.ANKLE_GIVES_WAY -> Txt("The ankle keeps giving way", "Das Sprunggelenk knickt immer wieder um")
    }

    /** Why a sign matters, for the ones with a reason of their own (research/safety-regulation.md §4.2). */
    fun signWhy(s: WarningSign): Txt? = when (s) {
        WarningSign.FINGERTIP_CANNOT_BEND -> Txt(
            "This can be a torn tendon at the fingertip. It's best repaired within about a week, so please get it checked today or tomorrow.",
            "Das kann eine abgerissene Sehne am Fingerendglied sein. Sie wird am besten innerhalb etwa einer Woche versorgt – bitte heute oder morgen abklären lassen.")
        WarningSign.BOWSTRINGING -> Txt(
            "A tendon that lifts away from the bone usually means more than one pulley is torn. A hand specialist should look at it soon.",
            "Wenn sich die Sehne vom Knochen abhebt, sind meist mehrere Ringbänder gerissen. Das sollte sich bald jemand aus der Handchirurgie ansehen.")
        WarningSign.POP_WITH_SWELLING -> Txt(
            "After a pop with swelling or bruising, an ultrasound shows how much is injured. Some injuries heal better when treated early – best within the first week.",
            "Nach einem Knall mit Schwellung oder Bluterguss zeigt ein Ultraschall, wie viel verletzt ist. Manche Verletzungen heilen besser, wenn sie früh behandelt werden – am besten in der ersten Woche.")
        WarningSign.HEEL_OF_PALM -> Txt(
            "Pain there can come from a small wrist bone (hamate) that an X-ray or MRI shows.",
            "Schmerz dort kann von einem kleinen Handwurzelknochen (Hamulus) kommen, das zeigt ein Röntgen- oder MRT-Bild.")
        WarningSign.THUMB_BASE_AFTER_FALL -> Txt(
            "After a fall on the hand, pain at the base of the thumb can be a scaphoid fracture that early X-rays miss.",
            "Nach einem Sturz auf die Hand kann Schmerz am Daumenansatz ein Kahnbeinbruch sein, den frühe Röntgenbilder übersehen.")
        WarningSign.ELBOW_POP -> Txt(
            "This can be a torn biceps tendon. Whether surgery helps is best decided within days.",
            "Das kann ein Abriss der Bizepssehne sein. Ob eine Operation hilft, wird am besten innerhalb weniger Tage entschieden.")
        WarningSign.SHOULDER_CAME_OUT -> Txt(
            "After a first dislocation, especially when young, it's worth talking with a doctor about the risk of it happening again.",
            "Nach einer ersten Ausrenkung, besonders in jungen Jahren, lohnt das Gespräch mit Ärztin oder Arzt über das Risiko, dass es wieder passiert.")
        WarningSign.HEEL_HOOK_POP -> Txt(
            "A torn hamstring tendon at the sit bone is best assessed within days, because the surgery decision is time-sensitive.",
            "Ein Abriss der Oberschenkel-Sehne am Sitzbein wird am besten in wenigen Tagen angeschaut, weil die OP-Entscheidung zeitkritisch ist.")
        WarningSign.NO_TIPTOE_AFTER_POP -> Txt(
            "This can be a torn Achilles tendon – please get it checked today or tomorrow.",
            "Das kann ein Achillessehnenriss sein – bitte heute oder morgen abklären lassen.")
        WarningSign.CANNOT_WALK_FOUR_STEPS, WarningSign.ANKLE_BONE_TENDER, WarningSign.HEEL_AFTER_LANDING -> Txt(
            "This is one of the signs doctors use to decide on an X-ray (Ottawa rules).",
            "Das ist eines der Zeichen, nach denen Ärzte über ein Röntgenbild entscheiden (Ottawa-Regeln).")
        else -> null
    }

    val UNDER_18 = Txt(
        "Under 18? Get finger pain checked. While you're growing, pain at the middle finger joint can come from the growth plate. An X-ray or MRI shows it, and it needs a different plan. Please see a doctor before you start, and leave out max hangs and campus boarding.",
        "Unter 18? Fingerschmerzen abklären lassen. Solange du wächst, können Schmerzen am Fingermittelgelenk von der Wachstumsfuge kommen. Das zeigt ein Röntgen- oder MRT-Bild, und dann braucht es einen anderen Plan. Geh bitte vor dem Start zu einer Ärztin oder einem Arzt und lass Maximaltests und Campusboard weg.",
    )

    fun alertTitle(u: Urgency): Txt = when (u) {
        Urgency.EMERGENCY -> Txt("Please get this checked now", "Bitte jetzt abklären lassen")
        Urgency.DAYS -> Txt("Best get this checked in the next few days", "Am besten in den nächsten Tagen abklären lassen")
        Urgency.ROUTINE -> Txt("Worth having it looked at", "Lohnt sich, es anschauen zu lassen")
    }

    fun alertText(u: Urgency): Txt = when (u) {
        Urgency.EMERGENCY -> Txt(
            "What you ticked can mean an injury that needs quick treatment, like a fracture, a dislocation, an infection or a nerve injury. Call 112 or go to an emergency department, and let someone else drive. Don't train this area until it's been checked.",
            "Was du angekreuzt hast, kann auf eine Verletzung hindeuten, die schnell behandelt werden sollte – etwa einen Bruch, eine Ausrenkung, eine Entzündung oder einen Nervenschaden. Ruf 112 an oder fahr in eine Notaufnahme, am besten nicht selbst. Trainiere diese Stelle erst, wenn sie angeschaut wurde.")
        Urgency.DAYS -> Txt(
            "What you ticked is best looked at by a doctor in the next few days – some injuries heal better when treated early. Until then keep it easy and pain-free, and don't start loading it.",
            "Was du angekreuzt hast, sollte sich am besten in den nächsten Tagen eine Ärztin oder ein Arzt ansehen – manche Verletzungen heilen besser, wenn sie früh behandelt werden. Bis dahin: locker und schmerzfrei bleiben und noch nicht belasten.")
        Urgency.ROUTINE -> Txt(
            "What you ticked doesn't need an emergency visit, but a doctor or physio should have a look soon. Your plan can wait for that.",
            "Was du angekreuzt hast, ist kein Notfall, aber eine Ärztin, ein Arzt oder eine Physio sollte es bald anschauen. Dein Plan kann so lange warten.")
    }

    val NOTHING_TICKED = Txt(
        "Nothing ticked – good. That doesn't rule anything out; if something changes, check this list again.",
        "Nichts angekreuzt – gut. Ausschließen lässt sich damit nichts; wenn sich etwas ändert, schau die Liste wieder an.",
    )

    fun finding(f: InjuryFinding): Txt = when (f) {
        InjuryFinding.PULLEY_STRAIN -> Txt("Pulley strain (grade I)", "Ringband-Zerrung (Grad I)")
        InjuryFinding.PULLEY_PARTIAL -> Txt("Partial pulley tear (grade II)", "Ringband-Teilriss (Grad II)")
        InjuryFinding.PULLEY_RUPTURE -> Txt("Complete pulley tear (grade III)", "Ringband-Riss komplett (Grad III)")
        InjuryFinding.PULLEY_MULTIPLE -> Txt("Several pulleys torn (grade IV)", "Mehrere Ringbänder gerissen (Grad IV)")
        InjuryFinding.TENOSYNOVITIS -> Txt("Tendon sheath inflammation (tenosynovitis)", "Sehnenscheidenentzündung")
        InjuryFinding.LUMBRICAL -> Txt("Lumbrical strain or tear", "Lumbrikalis-Zerrung oder -Riss")
        InjuryFinding.PIP_CAPSULITIS -> Txt("Middle joint irritation (capsulitis)", "Reizung des Mittelgelenks (Kapsulitis)")
        InjuryFinding.COLLATERAL_LIGAMENT -> Txt("Collateral ligament sprain", "Seitenband-Verletzung")
        InjuryFinding.VOLAR_PLATE -> Txt("Volar plate injury", "Verletzung der palmaren Platte")
        InjuryFinding.FLEXOR_TENDON_TEAR -> Txt("Flexor tendon tear", "Beugesehnen-Riss")
        InjuryFinding.GROWTH_PLATE -> Txt("Growth plate injury", "Wachstumsfugen-Verletzung")
        InjuryFinding.ROTATOR_CUFF -> Txt("Rotator cuff / subacromial pain", "Rotatorenmanschette / Impingement")
        InjuryFinding.SLAP_BICEPS -> Txt("SLAP lesion / long biceps tendon", "SLAP-Läsion / lange Bizepssehne")
        InjuryFinding.SHOULDER_INSTABILITY -> Txt("Shoulder instability or dislocation", "Instabile Schulter oder Ausrenkung")
        InjuryFinding.MEDIAL_ELBOW -> Txt("Inner elbow (golfer's elbow)", "Innenseite Ellenbogen (Golferellenbogen)")
        InjuryFinding.LATERAL_ELBOW -> Txt("Outer elbow (tennis elbow)", "Außenseite Ellenbogen (Tennisellenbogen)")
        InjuryFinding.FRONT_ELBOW -> Txt("Front of the elbow (brachialis / biceps)", "Vorderseite Ellenbogen (Brachialis / Bizeps)")
        InjuryFinding.ULNAR_WRIST -> Txt("Little-finger side of the wrist (TFCC)", "Kleinfingerseite Handgelenk (TFCC)")
        InjuryFinding.WRIST_TENDON -> Txt("Wrist tendons", "Sehnen am Handgelenk")
        InjuryFinding.ANKLE_SPRAIN -> Txt("Sprained ankle", "Umgeknickt (Bänder)")
        InjuryFinding.KNEE_INNER -> Txt("Inner knee (meniscus / inner ligament)", "Innenseite Knie (Meniskus / Innenband)")
        InjuryFinding.KNEE_OUTER -> Txt("Outer knee (outer ligament / IT band)", "Außenseite Knie (Außenband / Tractus)")
        InjuryFinding.HAMSTRING -> Txt("Hamstring (back of the thigh)", "Hintere Oberschenkelmuskulatur")
        InjuryFinding.BACK_NECK -> Txt("Back or neck pain without warning signs", "Rücken- oder Nackenschmerz ohne Warnzeichen")
        InjuryFinding.FRACTURE -> Txt("Fracture", "Knochenbruch")
        InjuryFinding.OTHER -> Txt("Something else", "Etwas anderes")
    }

    /** Notes that change how the stages are used for a finding. */
    fun findingNotes(f: InjuryFinding): List<Txt> = when (f) {
        InjuryFinding.PULLEY_STRAIN -> listOf(A4)
        InjuryFinding.PULLEY_PARTIAL -> listOf(Txt(
            "After a partial tear about 10 days of rest in a splint or tape are usual before loading.",
            "Nach einem Teilriss sind etwa 10 Tage Ruhe mit Schiene oder Tape üblich, bevor belastet wird."), A4)
        InjuryFinding.PULLEY_RUPTURE -> listOf(Txt(
            "After a complete tear, 10–14 days of rest and then a protective ring for about 2 months are usual. Start loading only when your doctor agrees, and keep to 0–1 of 10 in the first weeks.",
            "Nach einem kompletten Riss sind 10–14 Tage Ruhe und danach etwa 2 Monate ein Schutzring üblich. Belaste erst, wenn deine Ärztin oder dein Arzt einverstanden ist, und halte dich in den ersten Wochen an 0–1 von 10."), A4)
        InjuryFinding.PULLEY_MULTIPLE -> listOf(OWN_PLAN_NOTE)
        InjuryFinding.TENOSYNOVITIS -> listOf(Txt(
            "With tendon sheath inflammation, less load is the core. Mild pain up to 3 of 10 that settles by the next morning is usually acceptable here. It often takes weeks to months.",
            "Bei einer Sehnenscheidenentzündung ist weniger Belastung der Kern. Leichter Schmerz bis 3 von 10, der bis zum nächsten Morgen abklingt, gilt hier meist als in Ordnung. Sie dauert oft Wochen bis Monate."))
        InjuryFinding.LUMBRICAL -> listOf(Txt(
            "Avoid pockets and mixed grips (one finger straight, the others bent). Stretch gently in the painful position with very little load. Open hand and half crimp are usually fine.",
            "Meide Löcher und Mischgriffe (ein Finger gestreckt, die anderen gebeugt). Dehn sanft in der schmerzhaften Stellung mit sehr wenig Last. Offen und halb aufgestellt sind meist unproblematisch."))
        InjuryFinding.PIP_CAPSULITIS -> listOf(Txt(
            "Unload about 2 weeks, move the joint daily, crimp less. Full crimp is optional and comes last.",
            "Etwa 2 Wochen entlasten, das Gelenk täglich bewegen, weniger crimpen. Voll aufgestellt ist optional und kommt zuletzt."))
        InjuryFinding.COLLATERAL_LIGAMENT -> listOf(Txt(
            "With a stable joint, buddy tape (to the neighbouring finger) for 2–4 weeks is usual. If the joint wobbles, it belongs with a hand surgeon.",
            "Bei stabilem Gelenk ist Buddy-Tape (an den Nachbarfinger) für 2–4 Wochen üblich. Wackelt das Gelenk, gehört es in die Handchirurgie."))
        InjuryFinding.VOLAR_PLATE -> listOf(Txt(
            "Usual are 2–4 weeks in a splint as your doctor says, then buddy tape.",
            "Üblich sind 2–4 Wochen Schiene nach ärztlicher Vorgabe, danach Buddy-Tape."))
        InjuryFinding.FLEXOR_TENDON_TEAR -> listOf(Txt(
            "A torn flexor tendon is repaired surgically, best within about a week. Afterwards your hand surgeon's plan applies.",
            "Ein Beugesehnen-Riss wird operiert, am besten innerhalb etwa einer Woche. Danach gilt der Plan deiner Handchirurgie."), OWN_PLAN_NOTE)
        InjuryFinding.GROWTH_PLATE -> listOf(Txt(
            "A growth plate injury needs a doctor alongside. No max training, no campus, until your doctor says otherwise.",
            "Eine Wachstumsfugen-Verletzung braucht ärztliche Begleitung. Kein Maximaltraining, kein Campus, bis deine Ärztin oder dein Arzt es anders sagt."), OWN_PLAN_NOTE)
        InjuryFinding.SHOULDER_INSTABILITY -> listOf(Txt(
            "The stop signal is the feeling the shoulder could slip out – not the pain. Gastons, dynos and falls come last.",
            "Das Stoppsignal ist das Gefühl, die Schulter könnte herausspringen – nicht der Schmerz. Gastons, Dynamos und Stürze kommen zuletzt."), OWN_PLAN_NOTE)
        InjuryFinding.SLAP_BICEPS -> listOf(Txt(
            "Having it checked by a sports orthopaedist is recommended. Underclings and lock-offs come last.",
            "Eine Abklärung in der Sportorthopädie wird empfohlen. Untergriffe und Blockieren kommen zuletzt."))
        InjuryFinding.MEDIAL_ELBOW, InjuryFinding.LATERAL_ELBOW -> listOf(Txt(
            "Tingling in the ring and little finger: don't train into it; if it stays, have it checked (ulnar nerve).",
            "Kribbeln im Ring- und kleinen Finger: nicht hineintrainieren; bleibt es, abklären lassen (Ellennerv)."))
        InjuryFinding.FRONT_ELBOW -> listOf(Txt(
            "A pop with a bruise in the crease or a changed biceps shape: see a doctor within days.",
            "Ein Knall mit Bluterguss in der Beuge oder veränderter Bizepsform: in wenigen Tagen zum Arzt."))
        InjuryFinding.ULNAR_WRIST -> listOf(Txt(
            "Scaphoid, hamate and an unstable joint should be ruled out first. Pain beyond 4–6 weeks despite unloading: have it checked again.",
            "Kahnbein, Hamulus und ein instabiles Gelenk sollten vorher ausgeschlossen sein. Schmerzen über 4–6 Wochen trotz Entlastung: erneut abklären lassen."))
        InjuryFinding.ANKLE_SPRAIN -> listOf(Txt(
            "A brace or tape in the first weeks is usual. Rope instead of bouldering, climb down instead of jumping.",
            "Bandage oder Tape in den ersten Wochen ist üblich. Seil statt Bouldern, abklettern statt abspringen."))
        InjuryFinding.KNEE_INNER, InjuryFinding.KNEE_OUTER -> listOf(Txt(
            "Rope instead of bouldering, no jumping off. A knee that locks belongs with a doctor within days.",
            "Seil statt Bouldern, keine Absprünge. Ein Knie, das blockiert, gehört in wenigen Tagen zum Arzt."))
        InjuryFinding.HAMSTRING -> listOf(Txt(
            "Heel hooks come last: first low and on big footholds.", "Heel Hooks kommen zuletzt: erst flach und an großen Tritten."))
        InjuryFinding.BACK_NECK -> listOf(Txt(
            "Staying active is usually better than resting. Neck pain from belaying: belay glasses and gentle neck mobility help.",
            "Aktiv bleiben ist meist besser als Schonen. Nackenschmerz vom Sichern: Sicherungsbrille und sanfte Nackenmobilisation helfen."))
        InjuryFinding.FRACTURE -> listOf(OWN_PLAN_NOTE)
        InjuryFinding.ROTATOR_CUFF, InjuryFinding.WRIST_TENDON, InjuryFinding.OTHER -> emptyList()
    }

    private val A4 = Txt(
        "With an A4 pulley, open grips can hurt as well – open hand is not automatically pain-free.",
        "Beim A4-Ringband können auch offene Griffe weh tun – offen ist nicht automatisch schmerzfrei.",
    )
    val OWN_PLAN_NOTE = Txt(
        "Here your doctor's or hand surgeon's plan applies. The CruxCoach stages are not made for this – use \"My physio's plan\" and log it here.",
        "Hier gilt der Plan deiner Ärztin, deines Arztes oder deiner Handchirurgie. Die CruxCoach-Stufen sind dafür nicht gemacht – nimm \"Plan meiner Physio\" und trag es hier ein.",
    )

    /** The usual course reported in the literature, as information – never a schedule (fingers.md §3.1, other-injuries.md). */
    fun typicalCourse(f: InjuryFinding): Txt? = when (f) {
        InjuryFinding.PULLEY_STRAIN -> Txt(
            "Easy climbing usually after 2–4 weeks, full climbing around 6 weeks; tape when climbing for about 3 months.",
            "Leichtes Klettern meist nach 2–4 Wochen, volles Klettern um 6 Wochen; beim Klettern etwa 3 Monate tapen.")
        InjuryFinding.PULLEY_PARTIAL -> Txt(
            "About 10 days of rest, then 2–4 weeks of careful loading; full climbing usually after 6–10 weeks; tape for about 3 months.",
            "Etwa 10 Tage Ruhe, dann 2–4 Wochen vorsichtig belasten; volles Klettern meist nach 6–10 Wochen; etwa 3 Monate tapen.")
        InjuryFinding.PULLEY_RUPTURE -> Txt(
            "10–14 days of rest, then a ring for about 2 months; easy climbing after 6–8 weeks at the earliest, full climbing around 3 months; tape for about 6 months. Getting back to the old level took 8.8 months on average after complete tears (47 climbers treated with a ring).",
            "10–14 Tage Ruhe, dann etwa 2 Monate Ring; leichtes Klettern frühestens nach 6–8 Wochen, volles Klettern um 3 Monate; etwa 6 Monate tapen. Zurück aufs alte Niveau dauerte es nach kompletten Rissen im Schnitt 8,8 Monate (47 Kletternde mit Ring).")
        InjuryFinding.PULLEY_MULTIPLE -> Txt(
            "Usually surgery or a ring started within days; full climbing around 6 months after surgery; tape for 12 months or more.",
            "Meist Operation oder ein Ring, der in den ersten Tagen beginnt; volles Klettern um 6 Monate nach der OP; 12 Monate oder länger tapen.")
        InjuryFinding.TENOSYNOVITIS -> Txt(
            "Often weeks to months (30 weeks on average in one clinic series); three in four climbers got back to their level.",
            "Oft Wochen bis Monate (im Schnitt 30 Wochen in einer Klinik-Serie); drei von vier Kletternden kamen auf ihr Niveau zurück.")
        InjuryFinding.LUMBRICAL -> Txt(
            "In a series of 60 climbers all recovered with adapted climbing; recheck after 2–3 weeks if it doesn't settle.",
            "In einer Serie mit 60 Kletternden erholten sich alle mit angepasstem Klettern; nach 2–3 Wochen erneut anschauen lassen, wenn es nicht besser wird.")
        InjuryFinding.PIP_CAPSULITIS -> Txt(
            "About 2 weeks of unloading, then a gradual return; about 6 weeks in a published case.",
            "Etwa 2 Wochen entlasten, dann schrittweise zurück; in einem veröffentlichten Fall etwa 6 Wochen.")
        InjuryFinding.COLLATERAL_LIGAMENT, InjuryFinding.VOLAR_PLATE -> Txt(
            "Stable joint: 2–4 weeks of buddy tape or splint, then a gradual return.",
            "Stabiles Gelenk: 2–4 Wochen Buddy-Tape oder Schiene, dann schrittweise zurück.")
        InjuryFinding.FLEXOR_TENDON_TEAR -> Txt(
            "Surgery within about 7–10 days, then months of building up load with your hand surgeon.",
            "Operation innerhalb etwa 7–10 Tagen, danach Monate Belastungsaufbau mit deiner Handchirurgie.")
        InjuryFinding.GROWTH_PLATE -> Txt(
            "At least about 6 weeks without finger training, reduced load for 3–12 months – only with a doctor alongside.",
            "Mindestens etwa 6 Wochen ohne Fingertraining, reduzierte Belastung für 3–12 Monate – nur mit ärztlicher Begleitung.")
        InjuryFinding.ROTATOR_CUFF -> Txt(
            "Exercise is the first choice here (strong evidence); surgery did no better than placebo surgery. Usual are 6–12 weeks of strengthening; no progress after 12 weeks: see a specialist. MRI findings are common in climbers without any pain.",
            "Training ist hier die erste Wahl (starke Evidenz); eine OP war nicht besser als eine Schein-OP. Üblich sind 6–12 Wochen Kräftigung; ohne Fortschritt nach 12 Wochen: fachärztlich abklären. MRT-Befunde sind bei Kletternden auch ohne Schmerzen häufig.")
        InjuryFinding.SLAP_BICEPS -> Txt(
            "Conservative treatment worked for about 78 % of athletes who completed it, usually within 6 months.",
            "Konservativ klappte es bei etwa 78 % der Sportler, die es durchzogen, meist innerhalb von 6 Monaten.")
        InjuryFinding.SHOULDER_INSTABILITY -> Txt(
            "After a first dislocation in young people it happens again in about half without surgery and in about 6 % after stabilisation; 93 % of climbers got back to climbing after surgery.",
            "Nach einer ersten Ausrenkung in jungen Jahren passiert es ohne OP bei etwa der Hälfte wieder, nach einer Stabilisierung bei etwa 6 %; 93 % der Kletternden kamen nach der OP zurück ans Klettern.")
        InjuryFinding.LATERAL_ELBOW, InjuryFinding.MEDIAL_ELBOW -> Txt(
            "Usual are 3 × 15 repetitions over 6–12 weeks, from holds to slow lowering. Cortisone injections help for a few weeks but did worse after a year.",
            "Üblich sind 3 × 15 Wiederholungen über 6–12 Wochen, von Halteübungen zu langsamem Absenken. Kortisonspritzen helfen ein paar Wochen, nach einem Jahr stand es damit aber schlechter.")
        InjuryFinding.FRONT_ELBOW -> Txt(
            "Gradual loading over several weeks; underclings and steep pulling come last.",
            "Schrittweiser Belastungsaufbau über mehrere Wochen; Untergriffe und steiles Ziehen kommen zuletzt.")
        InjuryFinding.ULNAR_WRIST -> Txt(
            "Unload or splint, then load turning and tilting, then stability, then climbing. For TFCC tears this did as well as surgery.",
            "Entlasten oder Schiene, dann Drehen und Kippen belasten, dann Stabilität, dann Klettern. Bei TFCC-Rissen war das so gut wie eine Operation.")
        InjuryFinding.WRIST_TENDON -> null
        InjuryFinding.ANKLE_SPRAIN -> Txt(
            "Early loading with a brace plus balance training is the usual approach. 8 weeks of balance training lower the risk of spraining it again.",
            "Frühe Belastung mit Bandage plus Gleichgewichtstraining ist das übliche Vorgehen. 8 Wochen Gleichgewichtstraining senken das Risiko, wieder umzuknicken.")
        InjuryFinding.KNEE_INNER, InjuryFinding.KNEE_OUTER -> Txt(
            "Stable ligament injuries and a knee that doesn't lock: exercise is usual; for degenerative meniscus tears exercise did as well as surgery.",
            "Stabile Bandverletzungen und ein Knie, das nicht blockiert: Training ist üblich; bei verschleißbedingten Meniskusrissen war Training so gut wie eine Operation.")
        InjuryFinding.HAMSTRING -> Txt(
            "Training with pain up to 4 of 10 did not slow the return in a trial and built more strength.",
            "Training mit Schmerz bis 4 von 10 hat die Rückkehr in einer Studie nicht verzögert und mehr Kraft aufgebaut.")
        InjuryFinding.BACK_NECK -> Txt(
            "For back and neck pain without warning signs, staying active is the usual approach.",
            "Bei Rücken- und Nackenschmerz ohne Warnzeichen ist aktiv bleiben das übliche Vorgehen.")
        InjuryFinding.FRACTURE, InjuryFinding.OTHER -> null
    }

    /** Optional nutrition note (owner's plan), with its weak evidence and the safety questions (loading-principles.md F1–F3). */
    val GELATIN = Txt(
        "Optional: 15 g gelatin with about 50 mg vitamin C, about 60 minutes before the first session of the day. In a small study (8 men) it raised a blood marker of collagen production; whether it helps a finger heal is not known. It's no reason to load more or sooner. Leave it out with a gelatin or meat allergy (alpha-gal) and ask your doctor first if you've had kidney stones.",
        "Optional: 15 g Gelatine mit etwa 50 mg Vitamin C, etwa 60 Minuten vor der ersten Einheit des Tages. In einer kleinen Studie (8 Männer) stieg damit ein Blutwert der Kollagenbildung; ob es beim Heilen eines Fingers hilft, ist nicht bekannt. Es ist kein Grund, mehr oder früher zu belasten. Bei Gelatine- oder Fleischallergie (Alpha-Gal) weglassen, nach Nierensteinen vorher ärztlich fragen.",
    )
    val GELATIN_SOURCES = listOf(
        Source("Shaw G et al. Vitamin C–enriched gelatin supplementation before intermittent activity augments collagen synthesis. Am J Clin Nutr 2017;105:136–143", SourceKind.TRIAL),
        Source("Lis DM, Baar K. Effects of different vitamin C–enriched collagen derivatives on collagen synthesis. Int J Sport Nutr Exerc Metab 2019;29:526–531", SourceKind.TRIAL),
    )
}
