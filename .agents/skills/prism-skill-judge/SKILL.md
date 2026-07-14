---
name: prism-skill-judge
description: "Multi-pass structural analysis of Agent Skills with mandatory adversarial self-correction. Evaluates knowledge delta, mindset+procedures, anti-patterns, spec compliance, progressive disclosure, freedom calibration, and pattern recognition. Outputs adversarially-verified findings table."
---

# Prism Skill Judge — Multi-Pass Skill Evaluation with Adversarial Self-Correction

## PHASE 1: DESIGN THE PIPELINE

You are a pipeline architect. The artifact is a Skill (SKILL.md + resources). Design 3-4 analytical passes specifically for evaluating Agent Skills.

### PASS 1 — Knowledge Delta & Redundancy Audit
**Role**: Quantify the Skill's knowledge delta — what it teaches vs what the model already knows.
**Instructions**:
- Partition every section into: **Expert** (genuinely unknown to model), **Activation** (known but needs trigger), **Redundant** (Claude definitely knows).
- Count paragraphs in each category. Calculate knowledge delta ratio = Expert / (Expert + Activation + Redundant).
- Flag sections explaining basics (what is X, how to write code, standard library usage) as Redundant.
- Flag decision trees, trade-offs from experience, edge cases from real work as Expert.
- Flag trigger reminders, checklists as Activation.
- Build a finding for each Redundant section: location, what it states, why redundant, tokens wasted.

### PASS 2 — Mindset + Domain Procedures Audit
**Role**: Verify the Skill transfers expert thinking patterns AND domain-specific procedures the model lacks.
**Instructions**:
- **Mindset patterns**: Identify explicit thinking frameworks ("Before X, ask: Purpose? Constraints? Differentiation?"). Score: 0-3 (none/weak/strong/explicit).
- **Domain procedures**: Identify workflows the model wouldn't know (e.g., "OOXML workflow: unpack → edit XML → validate → pack"). Score: 0-3 (none/basic/deep/authoritative).
- **Generic procedure detection**: Flag any generic steps (open file → edit → save) as Redundant Procedure.
- Build finding table: category (mindset/procedure/generic), location, quality score, verdict.

### PASS 3 — Anti-Pattern Quality & Spec Compliance
**Role**: Evaluate anti-pattern specificity and frontmatter/description compliance.
**Instructions**:
- **Anti-patterns**: Score 0-15. 0=none, 4-7=generic ("avoid errors"), 8-11=specific with some reasoning, 12-15=expert-grade with WHY from experience.
- **Spec compliance**: Check frontmatter (name, description). Description MUST answer: WHAT (capabilities), WHEN (trigger scenarios), KEYWORDS (search terms). Score 0-15.
- **Progressive disclosure**: Check Layer 1 (metadata always loaded), Layer 2 (body <500 lines, mandatory triggers), Layer 3 (resources with mandatory triggers + "Do NOT load" guidance). Score 0-15.

### PASS 4 — Freedom Calibration + Pattern Recognition
**Role**: Verify freedom calibration matches task fragility and pattern fidelity.
**Instructions**:
- **Freedom calibration**: For each task scenario, check if freedom level matches consequence severity. High freedom for creative (multiple valid approaches), low for fragile ops (one wrong byte corrupts). Score 0-15.
- **Pattern recognition**: Identify which of 5 patterns (Mindset/Navigation/Philosophy/Process/Tool) the Skill follows. Score 0-10 for fidelity. Check if Skill declares its pattern.

### PASS 5 — Adversarial Stress Test (MANDATORY, NOT DESIGNED IN PHASE 1)
**Role**: Attack own findings from Passes 1-4. For each conservation law, structural claim, or bug:
- What evidence would DISPROVE it?
- Overclaim? (stated structural when fixable / fixable when structural)
- Underclaim? (missed something implied by own analysis)
- What did ALL passes take for granted that might be wrong?
- Output: Retracted claims, added claims, revised findings table.

## PHASE 2: EXECUTE THE PIPELINE

Execute Passes 1-4 sequentially. Each pass receives the artifact + all previous analyses.
After Pass 4, execute mandatory Pass 5 (Adversarial).
Then proceed to Phase 3.

## PHASE 3: SYNTHESIS

Produce final output:

### Final Findings
- **Conservation law**: The structural property surviving adversarial scrutiny
- **Retracted claims**: What adversarial pass disproved (if any)
- **Findings table**: Every concrete issue — location, what breaks, severity, fixable or structural. Only findings surviving adversarial review.
- **Deepest finding**: What became visible ONLY because adversarial pass challenged the analytical passes

---

## Output Format

All passes output to `## Generated Pipeline`, `## Pass 1 Analysis`, `## Pass 2 Analysis`, `## Pass 3 Analysis`, `## Pass 4 Analysis`, `## Pass 5 Adversarial`, `## Final Findings`.

Artifact to analyze: **The Skill being judged** (provided by user at invocation).