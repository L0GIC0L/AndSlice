// Host test harness for the AndSlice engine. It drives the exact code the
// Android app uses, so slicing can be verified on a desktop or in CI:
//
//   andslice_cli --bundle Kobra3.ini [--printer P] [--print P] [--filament F]...
//                [--extruder OBJECT=N]... [--set key=value]... -o out.gcode model.stl [model2.stl ...]
//   andslice_cli --bundle Kobra3.ini --list [--printer P]
//
// --vendor adds more config bundles (PrusaSlicer vendor profiles), loaded after --bundle.
// --save TYPE:NAME saves the selected TYPE preset with the --set values as a user preset.
//
// --filament can be repeated, once per extruder (ACE slot). --extruder assigns
// the 1-based extruder N to the object with the 0-based index OBJECT.
// --paint OBJECT=MODE:STATE[:OPTION]... paints the object from straight above its center, as a
// touch would: MODE is supports, colors, seam or fuzzy, STATE the enforcer/blocker or extruder (0 erases), with
// an 8 mm brush. Options: fill (smart fill), heightN (a band N mm high), below (paint from
// under the bed), overhangsN (every overhang tilted less than N degrees, instead of a touch).
//
// --mirror OBJECT=AXIS mirrors along x, y or z; --split OBJECT=objects|parts splits where the mesh is
// not connected; --cut OBJECT=HEIGHT cuts across at HEIGHT mm, turning the upper part onto its cut face.
// --undo-check deletes the first object, undoes and redoes that, and paints and undoes the painting,
// printing the plate after each step; the slice then has the first object back.
// --adaptive OBJECT=Q gives the object variable layer heights at PrusaSlicer's quality/speed factor Q (0..1).
// --object-set OBJECT:KEY=VALUE overrides an option for one object, and
// --range OBJECT:MIN-MAX:KEY=VALUE for one object between two heights.
//
// --project IN.3mf opens a 3MF project as the plate (before the models are added), and
// --save-project OUT.3mf saves the plate with the settings once the models are added and painted.
#include <cstdio>
#include <cstring>
#include <iostream>
#include <string>
#include <vector>

#include <boost/filesystem.hpp>
#include <nlohmann/json.hpp>

#include "Engine.hpp"

using andslice::Engine;
using nlohmann::json;

static int usage()
{
    std::cerr << "usage: andslice_cli --bundle FILE [--vendor FILE]... [--printer NAME] [--print NAME] [--filament NAME]...\n"
                 "                    [--extruder OBJECT=N]... [--set KEY=VALUE]... (--list | -o OUT.gcode MODEL...)\n";
    return 2;
}

int main(int argc, char** argv)
{
    std::string bundle, printer, print, output;
    std::vector<std::string> vendors;
    std::string save, project, save_project;
    std::vector<std::string> filaments;
    std::vector<std::pair<std::string, std::string>> overrides;
    std::vector<std::pair<int, int>> extruders;
    std::vector<std::pair<int, std::string>> paints, object_sets, ranges;
    std::vector<std::pair<int, float>> adaptive;
    std::vector<std::pair<std::string, std::pair<int, std::string>>> edits;
    std::vector<std::string> models;
    bool list = false, undo_check = false;

    for (int i = 1; i < argc; ++i) {
        const std::string a = argv[i];
        auto next = [&]() -> std::string { return i + 1 < argc ? argv[++i] : std::string(); };
        if (a == "--bundle")        bundle = next();
        else if (a == "--vendor")   vendors.push_back(next());
        else if (a == "--save")     save = next();
        else if (a == "--project")  project = next();
        else if (a == "--save-project") save_project = next();
        else if (a == "--printer")  printer = next();
        else if (a == "--print")    print = next();
        else if (a == "--filament") filaments.push_back(next());
        else if (a == "--extruder") {
            const std::string kv = next();
            const size_t eq = kv.find('=');
            if (eq == std::string::npos)
                return usage();
            extruders.emplace_back(std::stoi(kv.substr(0, eq)), std::stoi(kv.substr(eq + 1)));
        }
        else if (a == "--paint") {
            const std::string kv = next();
            const size_t eq = kv.find('=');
            if (eq == std::string::npos)
                return usage();
            paints.emplace_back(std::stoi(kv.substr(0, eq)), kv.substr(eq + 1));
        }
        else if (a == "--mirror" || a == "--split" || a == "--cut") {
            const std::string spec = next();
            const size_t eq = spec.find('=');
            if (eq == std::string::npos)
                return usage();
            edits.emplace_back(a, std::make_pair(std::stoi(spec.substr(0, eq)), spec.substr(eq + 1)));
        }
        else if (a == "--adaptive" || a == "--object-set" || a == "--range") {
            const std::string spec = next();
            const size_t sep = spec.find(a == "--adaptive" ? '=' : ':');
            if (sep == std::string::npos)
                return usage();
            const int index = std::stoi(spec.substr(0, sep));
            if (a == "--adaptive")
                adaptive.emplace_back(index, std::stof(spec.substr(sep + 1)));
            else
                (a == "--range" ? ranges : object_sets).emplace_back(index, spec.substr(sep + 1));
        }
        else if (a == "-o")         output = next();
        else if (a == "--list")     list = true;
        else if (a == "--undo-check") undo_check = true;
        else if (a == "--set") {
            const std::string kv = next();
            const size_t eq = kv.find('=');
            if (eq == std::string::npos)
                return usage();
            overrides.emplace_back(kv.substr(0, eq), kv.substr(eq + 1));
        } else if (!a.empty() && a[0] == '-')
            return usage();
        else
            models.push_back(a);
    }
    if (bundle.empty() || (!list && save.empty() && save_project.empty() && (output.empty() || (models.empty() && project.empty()))))
        return usage();

    try {
        const auto data_dir = boost::filesystem::temp_directory_path() / "andslice-cli";
        Engine& engine = Engine::get();
        std::vector<std::string> bundles{ bundle };
        bundles.insert(bundles.end(), vendors.begin(), vendors.end());
        engine.init(data_dir.string(), data_dir.string(), bundles);

        const json presets = json::parse(engine.presets_json(printer));
        if (printer.empty())  printer  = presets["printer"];
        if (print.empty())    print    = presets["default_print"];
        if (filaments.empty()) filaments.push_back(presets["default_filament"]);

        if (!save.empty()) {
            const size_t colon = save.find(':');
            if (colon == std::string::npos)
                return usage();
            std::cout << "saved " << engine.save_preset(save.substr(0, colon), save.substr(colon + 1), printer, print,
                                                         filaments, overrides) << "\n";
            if (!list)
                return 0;
        }
        if (list) {
            std::cout << presets.dump(2) << "\n";
            const json defs = json::parse(engine.option_defs_json());
            std::cout << "option defs: print " << defs["print"].size() << ", filament " << defs["filament"].size()
                      << ", printer " << defs["printer"].size() << "\n";
            return 0;
        }

        engine.set_printer(printer);
        // Leave room for brims when placing several objects, as the app does.
        for (const auto& [key, value] : overrides)
            if (key == "brim_width")
                engine.set_arrange_distance(6.f + 2.f * std::stof(value));
        // Multi-material printers get a prime tower in the back right corner, as in the app.
        if (presets["extruders"].get<int>() > 1) {
            andslice::WipeTowerShape tower;
            tower.visible = true;
            tower.x = 190.f;
            tower.y = 215.f;
            tower.width = 45.f;
            tower.depth = 20.f;
            tower.brim = 5.f;
            engine.set_wipe_tower(tower);
        }
        if (!project.empty()) {
            json opened = json::parse(engine.open_project(project));
            const json& config = opened["config"];
            std::cout << "project " << project << ": " << opened["ids"].dump() << ", "
                      << (config.is_object() ? config.size() : 0) << " settings";
            if (config.is_object())
                for (const char* key : { "printer_settings_id", "print_settings_id", "filament_settings_id", "fill_density", "filament_colour" })
                    if (config.contains(key))
                        std::cout << ", " << key << "=" << config[key].get<std::string>();
            std::cout << "\n";
        }
        for (const std::string& m : models)
            std::cout << "loaded " << m << ": " << engine.add_model(m, "") << "\n";
        for (const auto& [what, spec] : edits) {
            const json now = json::parse(engine.objects_json());
            const uint64_t id = now.at(size_t(spec.first))["id"].get<uint64_t>();
            if (what == "--mirror")
                engine.mirror_object(id, spec.second == "x" ? 0 : spec.second == "y" ? 1 : 2);
            else if (what == "--split")
                std::cout << "split: " << engine.split_object(id, spec.second == "parts") << "\n";
            else
                std::cout << "cut: " << engine.cut_object(id, std::stod(spec.second), true, true, true) << "\n";
        }
        const json objects = json::parse(engine.objects_json());
        for (const auto& [index, extruder] : extruders)
            if (index >= 0 && index < int(objects.size()))
                engine.set_extruder(objects[index]["id"].get<uint64_t>(), -1, extruder);
        for (const auto& [index, spec] : paints) {
            if (index < 0 || index >= int(objects.size()))
                return usage();
            const json& o = objects[index];
            const int mode  = spec.rfind("colors", 0) == 0 ? andslice::PaintColors :
                              spec.rfind("seam", 0) == 0   ? andslice::PaintSeam :
                              spec.rfind("fuzzy", 0) == 0  ? andslice::PaintFuzzySkin : andslice::PaintSupports;
            const size_t colon = spec.find(':');
            const int state = colon == std::string::npos ? 1 : std::stoi(spec.substr(colon + 1));
            auto number_after = [&spec](const std::string& tag) {
                const size_t at = spec.find(":" + tag);
                return at == std::string::npos ? 0.f : std::stof(spec.substr(at + tag.size() + 1));
            };
            andslice::PaintParams params;
            params.state        = state;
            params.radius       = 8.f;
            params.height_range = number_after("height");
            params.tool = spec.find(":fill") != std::string::npos ? andslice::PaintFill :
                          params.height_range > 0.f              ? andslice::PaintHeightRange : andslice::PaintBrush;
            const float overhangs = number_after("overhangs");
            // :below looks up at the object from under the bed.
            const bool  below     = spec.find(":below") != std::string::npos;
            const float camera[3] = { o["offset"][0].get<float>(), o["offset"][1].get<float>(), below ? -500.f : 500.f };
            const float dir[3]    = { 0.f, 0.f, below ? 1.f : -1.f };
            engine.paint_begin(o["id"].get<uint64_t>(), mode);
            const bool hit = overhangs > 0.f ? engine.paint_overhangs(state, overhangs) : engine.paint_at(camera, dir, params, false);
            engine.paint_commit();
            std::cout << "paint " << spec << (hit ? " hit: " : " missed: ") << engine.paint_state_json() << "\n";
            engine.paint_end();
        }
        auto object_id = [&objects](int index) { return objects.at(size_t(index))["id"].get<uint64_t>(); };
        for (const auto& [index, quality] : adaptive)
            engine.set_layer_quality(object_id(index), quality);
        for (const auto& [index, kv] : object_sets) {
            const size_t eq = kv.find('=');
            engine.set_object_option(object_id(index), -1., kv.substr(0, eq), kv.substr(eq + 1));
        }
        for (const auto& [index, spec] : ranges) {
            // MIN-MAX:KEY=VALUE
            const size_t dash = spec.find('-'), colon = spec.find(':'), eq = spec.find('=');
            const double lo = std::stod(spec.substr(0, dash)), hi = std::stod(spec.substr(dash + 1, colon - dash - 1));
            const json settings = json::parse(engine.object_settings_json(object_id(index)));
            bool exists = false;
            for (const json& r : settings["ranges"])
                exists = exists || std::abs(r["min"].get<double>() - lo) < 1e-4;
            if (!exists)
                engine.edit_height_range(object_id(index), -1., lo, hi);
            engine.set_object_option(object_id(index), lo, spec.substr(colon + 1, eq - colon - 1), spec.substr(eq + 1));
        }
        if (undo_check) {
            auto count = [&engine] { return json::parse(engine.objects_json()).size(); };
            engine.push_undo();
            engine.remove_object(object_id(0));
            std::cout << "undo check: after delete " << count();
            std::cout << ", undo " << engine.undo() << " -> " << count();
            std::cout << ", redo " << engine.redo() << " -> " << count();
            std::cout << ", undo " << engine.undo() << " -> " << count() << " " << engine.undo_state_json();
            const json first = json::parse(engine.objects_json()).at(0);
            const float camera[3] = { first["offset"][0].get<float>(), first["offset"][1].get<float>(), 500.f };
            const float down[3] = { 0.f, 0.f, -1.f };
            andslice::PaintParams params;
            params.radius = 8.f;
            engine.paint_begin(first["id"].get<uint64_t>(), andslice::PaintSupports);
            engine.paint_at(camera, down, params, false);
            engine.paint_commit();
            engine.paint_end();
            std::cout << ", painted " << json::parse(engine.objects_json()).at(0)["support_painted"].dump();
            std::cout << ", undo " << engine.undo() << " -> painted " << json::parse(engine.objects_json()).at(0)["support_painted"].dump() << "\n";
        }
        if (!object_sets.empty() || !ranges.empty())
            std::cout << "object settings: " << engine.object_settings_json(object_id(0)).substr(0, 300) << "\n";
        std::cout << "plate: " << engine.objects_json() << "\n";

        andslice::SliceRequest req{ printer, print, filaments, overrides, output };
        if (!save_project.empty()) {
            engine.save_project(save_project, req);
            std::cout << "saved project " << save_project << " (" << boost::filesystem::file_size(save_project) << " bytes)\n";
            if (output.empty())
                return 0;
        }
        const std::string result = engine.slice(req, [](int percent, const std::string& msg) {
            std::cout << percent << "% " << msg << "\n";
        });
        json r = json::parse(result);
        if (r.contains("layers"))
            r["layers"] = r["layers"].size(); // keep the output short
        std::cout << r.dump(2) << "\n";
        return r.value("ok", false) ? 0 : 1;
    } catch (const std::exception& ex) {
        std::cerr << "error: " << ex.what() << "\n";
        return 1;
    }
}
