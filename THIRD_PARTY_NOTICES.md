# Third-party source notice

This rebuild derives its conventional track mounts, tuning, physics and Offroad
integration from Create Tracks 1.0.1 by qwxon, supplied by the project owner:
`tracks-neoforge-1.21.1-1.0.1.jar`.
SHA-256: b126b2522a129c13ebec6491b8602726bad9a3df201cf906468ba583796125c8.

The source was recovered from this binary, rather than from a verified upstream
source commit. The JAR's original LICENSE.md is included verbatim as
`META-INF/licenses/tracks-LICENSE.md`. It licenses code under MIT and contains
the original copyright notice for The Simulated Team / The Creators of Aeronautics.
Attribution to qwxon is preserved in addition to that original notice.

Changes include a separate namespace, build against the locked Sable/Create/
Simulated/Offroad dependencies, compile repairs, independent artwork/rendering,
diagnostics and explicitly documented compatibility fixes. Rebuild.2 changes
the track wheels to directly placeable blocks and uses an independent creative
tab without registering a Simulated resource-defined section.
Rebuild.3 separates fixed sprockets from independent road-wheel suspension,
localizes terrain sampling to each wheel, revises spring/damping response and
contact friction gating, and synchronizes server suspension state for rendering.
Rebuild.4 adds an original per-body soft-contact impulse solver using the full
inertia response to couple spring damping and friction across road wheels.

Rebuild.6 adds an original support-envelope belt planner and shared path representation,
with cached conventional-loop rendering and fixed drive-wheel anchor handling.
Rebuild.7 replaces the lower convex envelope with an original wheel-bottom-guided
path, clipped below wheel discs, while retaining the upper return envelope.
Targeted inspection of the supplied Tracks, Gearwork and Bits 'n' Tracks binaries
informed architectural comparisons only; no Gearwork or Bits 'n' Tracks code or
assets were copied for this change.
Rebuild.8 attempted to correct excessive intermediate lower wraps with independently implemented
flat-and-slope links, using bounded geometric clearance checks to place each corner.
End wraps remain separate. No Gearwork model transforms, constants or code were reused.
The rebuild.6/7/8 path implementations failed user acceptance and were removed
from production in rebuild.9. Rebuild.9 introduces an original node-owned section
layout and separate suspension deformation/cache, with a conventional local-sole
profile and projected penetration diagnostics. This architectural build does not
claim that all poses of that profile are visually valid. Targeted inspection of
Tracks 1.0.1, Gearwork 1.0.0, Bits 'n' Tracks 1.0.3.1 and Bits 'n' Bobs 2.3.0
informed the separation of responsibilities; no Gearwork/BnT/BnB implementation
or artwork is included.

No Trackwork or Gearwork artwork is distributed. No Gearwork or Bits 'n' Tracks
CE implementation is included. Runtime dependencies remain separate mods and
retain their respective licenses. User-supplied wheel meshes and belt textures
are original project inputs, not extracted reference-mod artwork.

Rebuild.10 retires the conventional local-sole planner from runtime. An original
ordered-assembly implementation adds server-owned selection sessions, persistent
assembly identities and committed node sides, exact tangent/arc geometry,
external-run intersection trimming under suspension, shared preview/rendering,
thickness, and idempotent removal refunds. Targeted inspection of the supplied
BnB 2.3.0 and BnT 1.0.3.1 informed the two-frontier/lookahead routing policy.
No reference-mod implementation or artwork is included for these additions.
Geometry validity checks are not a claim of complete visual/gameplay acceptance.

Rebuild.11 updates original assembly interactions: survival dismantling refunds
regardless of creation mode, removal context preserves Create's cancellation
handling, explicit GUI pull-sync replaces automatic recovery, and position edits
support per-wheel, same-role and assembly scopes. Drive offsets are independent
of suspension, with migration of previously ignored drive offset data.
