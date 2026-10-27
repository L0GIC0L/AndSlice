// Replacement for PrusaSlicer's libslic3r/ArrangeHelper.cpp on Android.
//
// The original implements sequential ("complete objects") arrangement with
// libseqarrange, which depends on the Z3 SMT solver. Z3 is not built for
// Android, so the solver-based collision check is skipped. Print::validate()
// still runs PrusaSlicer's classic extruder-clearance check for sequential
// prints, so this only removes the GUI arrangement helper.
#include "libslic3r/ArrangeHelper.hpp"

#include "libslic3r/Exception.hpp"
#include "libslic3r/Model.hpp"

namespace Slic3r {

void arrange_model_sequential(Model&, const ConfigBase&)
{
    throw RuntimeError("Sequential arrangement is not available in this build.");
}

std::optional<std::pair<std::string, std::string>> check_seq_conflict(const Model&, const ConfigBase&)
{
    return std::nullopt;
}

} // namespace Slic3r
